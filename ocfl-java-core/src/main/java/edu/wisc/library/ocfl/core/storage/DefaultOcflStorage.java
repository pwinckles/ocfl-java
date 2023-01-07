/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 University of Wisconsin Board of Regents
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package edu.wisc.library.ocfl.core.storage;

import static edu.wisc.library.ocfl.api.OcflConstants.INVENTORY_SIDECAR_PREFIX;

import edu.wisc.library.ocfl.api.OcflConstants;
import edu.wisc.library.ocfl.api.OcflFileRetriever;
import edu.wisc.library.ocfl.api.exception.CorruptObjectException;
import edu.wisc.library.ocfl.api.exception.FixityCheckException;
import edu.wisc.library.ocfl.api.exception.NotFoundException;
import edu.wisc.library.ocfl.api.exception.ObjectOutOfSyncException;
import edu.wisc.library.ocfl.api.exception.OcflFileAlreadyExistsException;
import edu.wisc.library.ocfl.api.exception.OcflIOException;
import edu.wisc.library.ocfl.api.exception.OcflNoSuchFileException;
import edu.wisc.library.ocfl.api.exception.OcflStateException;
import edu.wisc.library.ocfl.api.io.FixityCheckInputStream;
import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import edu.wisc.library.ocfl.api.model.ObjectVersionId;
import edu.wisc.library.ocfl.api.model.OcflVersion;
import edu.wisc.library.ocfl.api.model.ValidationResults;
import edu.wisc.library.ocfl.api.model.VersionNum;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.ObjectPaths;
import edu.wisc.library.ocfl.core.extension.OcflExtensionConfig;
import edu.wisc.library.ocfl.core.extension.storage.layout.OcflStorageLayoutExtension;
import edu.wisc.library.ocfl.core.inventory.SidecarMapper;
import edu.wisc.library.ocfl.core.model.Inventory;
import edu.wisc.library.ocfl.core.model.RevisionNum;
import edu.wisc.library.ocfl.core.path.constraint.LogicalPathConstraints;
import edu.wisc.library.ocfl.core.path.constraint.PathConstraintProcessor;
import edu.wisc.library.ocfl.core.storage.common.Listing;
import edu.wisc.library.ocfl.core.storage.common.ObjectProperties;
import edu.wisc.library.ocfl.core.storage.common.Storage;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.NamasteTypeFile;
import edu.wisc.library.ocfl.core.util.UncheckedFiles;
import edu.wisc.library.ocfl.core.validation.Validator;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the core logic for manipulating OCFL objects.
 */
public class DefaultOcflStorage extends AbstractOcflStorage {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOcflStorage.class);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final String MEDIA_TYPE_TEXT = "text/plain; charset=UTF-8";
    private static final String MEDIA_TYPE_JSON = "application/json; charset=UTF-8";

    private final PathConstraintProcessor logicalPathConstraints;
    private final Storage storage;
    private final OcflStorageInitializer initializer;
    private OcflStorageLayoutExtension storageLayoutExtension;
    private final Validator validator;
    private final boolean verifyInventoryDigest;

    /**
     * This retry policy is used for retrying failed inventory installs
     */
    private final RetryPolicy<Void> invRetry;

    /**
     * Create a new builder.
     *
     * @return builder
     */
    public static OcflStorageBuilder builder() {
        return new OcflStorageBuilder();
    }

    /**
     * Creates a new DefaultOcflStorage object.
     *
     * <p>{@link #initializeStorage} must be called before using this object.
     *
     * @see OcflStorageBuilder
     *
     * @param storage the abstraction over the underlying storage system that contains the OCFL repository
     * @param verifyInventoryDigest true if inventory digests should be verified on read
     * @param initializer initializes a new OCFL repo
     */
    public DefaultOcflStorage(Storage storage, boolean verifyInventoryDigest, OcflStorageInitializer initializer) {
        this.storage = Enforce.notNull(storage, "storage cannot be null");
        this.verifyInventoryDigest = verifyInventoryDigest;
        this.initializer = Enforce.notNull(initializer, "initializer cannot be null");
        this.logicalPathConstraints = LogicalPathConstraints.constraintsWithBackslashCheck();
        this.validator = new Validator(storage);
        this.invRetry = new RetryPolicy<Void>()
                .handle(RuntimeException.class)
                .withBackoff(10, 200, ChronoUnit.MILLIS, 1.5)
                .withMaxRetries(10);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Inventory loadInventory(String objectId) {
        ensureOpen();

        LOG.debug("Load inventory for object <{}>", objectId);

        Inventory inventory = null;

        var objectRootPath = objectRootPath(objectId);
        var objectProps = examineObject(objectRootPath);

        if (objectProps.getOcflVersion() != null) {
            if (objectProps.getDigestAlgorithm() == null) {
                throw new CorruptObjectException(String.format("Object %s is missing its root sidecar file", objectId));
            }

            var hasMutableHead = false;
            if (objectProps.hasExtensions()) {
                hasMutableHead = loadObjectExtensions(objectRootPath).contains(OcflConstants.MUTABLE_HEAD_EXT_NAME);
            }

            if (hasMutableHead) {
                inventory = parseAndVerifyMutableInventory(objectId, objectProps.getDigestAlgorithm(), objectRootPath);
                ensureRootObjectHasNotChanged(inventory);
            } else {
                inventory = parseAndVerifyInventory(objectId, objectProps.getDigestAlgorithm(), objectRootPath);
            }

            if (!Objects.equals(objectId, inventory.getId())) {
                throw new CorruptObjectException(String.format(
                        "Expected object at %s to have id %s. Found: %s", objectRootPath, objectId, inventory.getId()));
            }
        }

        return inventory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getInventoryBytes(String objectId, VersionNum versionNum) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");
        Enforce.notNull(versionNum, "versionNum cannot be null");

        LOG.debug("Loading inventory bytes for object {} version {}", objectId, versionNum);

        var objectRootPath = objectRootPath(objectId);
        var versionPath = FileUtil.pathJoinFailEmpty(objectRootPath, versionNum.toString());

        var inventoryPath = ObjectPaths.inventoryPath(versionPath);

        try (var stream = storage.read(inventoryPath)) {
            return stream.readAllBytes();
        } catch (OcflNoSuchFileException e) {
            var mutableHeadInventoryPath = ObjectPaths.mutableHeadInventoryPath(objectRootPath);

            try (var mutableStream = storage.read(mutableHeadInventoryPath)) {
                var bytes = mutableStream.readAllBytes();
                var inv = inventoryMapper.readMutableHeadNoDigest(
                        "root", RevisionNum.R1, new ByteArrayInputStream(bytes));

                if (versionNum.equals(inv.getHead())) {
                    return bytes;
                }
            } catch (OcflNoSuchFileException e2) {
                // Ignore missing mutable head
            } catch (IOException e2) {
                throw OcflIOException.from(e2);
            }
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }

        throw new NotFoundException(
                String.format("No inventory could be found for object %s version %s", objectId, versionNum));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stream<String> listObjectIds() {
        LOG.debug("List object ids");

        return findOcflObjectRootDirs().map(objectRoot -> {
            return parseInventory(objectRoot).getId();
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void storeNewVersion(Inventory inventory, Path stagingDir, boolean upgradeOcflVersion) {
        ensureOpen();

        LOG.debug(
                "Store new version of object <{}> version <{}> revision <{}> from staging directory <{}>",
                inventory.getId(),
                inventory.getHead(),
                inventory.getRevisionNum(),
                stagingDir);

        if (inventory.hasMutableHead()) {
            storeNewMutableHeadVersion(inventory, stagingDir);
        } else {
            storeNewImmutableVersion(inventory, stagingDir, upgradeOcflVersion);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, OcflFileRetriever> getObjectStreams(Inventory inventory, VersionNum versionNum) {
        ensureOpen();

        LOG.debug("Get file streams for object <{}> version <{}>", inventory.getId(), versionNum);

        var version = inventory.ensureVersion(versionNum);
        var algorithm = inventory.getDigestAlgorithm();

        var map = new HashMap<String, OcflFileRetriever>(version.getState().size());

        version.getState().forEach((digest, paths) -> {
            var srcPath = inventory.storagePath(digest);
            paths.forEach(path -> {
                map.put(path, storage.readLazy(srcPath, algorithm, digest));
            });
        });

        return map;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reconstructObjectVersion(Inventory inventory, VersionNum versionNum, Path stagingDir) {
        ensureOpen();

        LOG.debug("Reconstruct object <{}> version <{}> in directory <{}>", inventory.getId(), versionNum, stagingDir);

        var version = inventory.ensureVersion(versionNum);
        var digestAlgorithm = inventory.getDigestAlgorithm();

        version.getState().forEach((id, files) -> {
            var srcPath = inventory.storagePath(id);

            for (var logicalPath : files) {
                logicalPathConstraints.apply(logicalPath);
                var destination = Paths.get(FileUtil.pathJoinFailEmpty(stagingDir.toString(), logicalPath));

                UncheckedFiles.createDirectories(destination.getParent());

                try (var stream = new FixityCheckInputStream(
                        new BufferedInputStream(storage.read(srcPath)), digestAlgorithm, id)) {
                    Files.copy(stream, destination);
                    stream.checkFixity();
                } catch (FixityCheckException e) {
                    throw new FixityCheckException(
                            String.format(
                                    "File %s in object %s failed its fixity check.", logicalPath, inventory.getId()),
                            e);
                } catch (IOException e) {
                    throw OcflIOException.from(e);
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purgeObject(String objectId) {
        ensureOpen();

        LOG.info("Purge object <{}>", objectId);

        var objectRoot = objectRootPath(objectId);

        try {
            storage.deleteDirectory(objectRoot);
        } catch (RuntimeException e) {
            throw new CorruptObjectException(
                    String.format(
                            "Failed to purge object %s at %s. The object may need to be deleted manually.",
                            objectId, objectRoot),
                    e);
        }

        try {
            storage.deleteEmptyDirsUp(FileUtil.parentPath(objectRoot));
        } catch (RuntimeException e) {
            LOG.warn("Failed to cleanup parent directories when purging object {}.", objectId, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rollbackToVersion(Inventory inventory, VersionNum versionNum) {
        ensureOpen();

        LOG.info("Rollback object <{}> to version {}", inventory.getId(), versionNum);

        var versionPath = objectVersionPath(inventory, versionNum);

        try {
            copyInventoryInternal(inventory, versionPath, inventory.getObjectRootPath());
        } catch (Exception e) {
            try {
                var previousVersionPath = objectVersionPath(inventory, inventory.getHead());
                copyInventoryInternal(inventory, previousVersionPath, inventory.getObjectRootPath());
            } catch (RuntimeException e1) {
                LOG.error(
                        "Failed to rollback inventory at {}. Object {} must be fixed manually.",
                        ObjectPaths.inventoryPath(inventory.getObjectRootPath()),
                        inventory.getId(),
                        e1);
            }
            throw e;
        }

        try {
            var currentVersion = inventory.getHead();

            while (currentVersion.compareTo(versionNum) > 0) {
                LOG.info("Purging object {} version {}", inventory.getId(), currentVersion);
                storage.deleteDirectory(objectVersionPath(inventory, currentVersion));
                currentVersion = currentVersion.previousVersionNum();
            }

            purgeMutableHead(inventory.getId());
        } catch (RuntimeException e) {
            throw new CorruptObjectException(
                    String.format(
                            "Object %s was corrupted while attempting to rollback to version %s. It must be manually remediated.",
                            inventory.getId(), versionNum),
                    e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void commitMutableHead(Inventory oldInventory, Inventory newInventory, Path stagingDir) {
        ensureOpen();

        LOG.debug("Commit mutable HEAD on object <{}>", newInventory.getId());

        var objectRoot = ObjectPaths.objectRoot(newInventory);

        ensureRootObjectHasNotChanged(newInventory);

        if (!hasMutableHead(newInventory.getObjectRootPath())) {
            throw new ObjectOutOfSyncException(String.format(
                    "Cannot commit mutable HEAD of object %s because a mutable HEAD does not exist.",
                    newInventory.getId()));
        }

        var versionPath = objectVersionPath(newInventory, newInventory.getHead());

        moveMutableHeadToVersionDirectory(newInventory, versionPath);

        try {
            try {
                // The inventory is written to the root first so that the mutable version can be recovered if the write
                // fails
                copyInventoryToRootWithRollback(newInventory, objectRoot, stagingDir);
            } catch (RuntimeException e) {
                rollbackMutableHeadVersionInstall(newInventory, objectRoot, versionPath);
                throw e;
            }

            try {
                copyInventoryInternal(newInventory, newInventory.getObjectRootPath(), versionPath);
            } catch (RuntimeException e) {
                LOG.warn(
                        "Failed to copy the inventory into object {} version {}.",
                        newInventory.getId(),
                        newInventory.getHead());
            }
        } catch (RuntimeException e) {
            try {
                storage.deleteDirectory(versionPath);
                rollbackInventory(newInventory);
            } catch (RuntimeException exception) {
                LOG.error(
                        "Failed to rollback new version installation in object {} at {}. It must be cleaned up manually.",
                        newInventory.getId(),
                        newInventory.getHead(),
                        e);
            }
            throw e;
        }

        try {
            purgeMutableHead(newInventory.getId());
        } catch (RuntimeException e) {
            LOG.error(
                    "Failed to cleanup mutable HEAD of object {} at {}. It must be deleted manually.",
                    newInventory.getId(),
                    ObjectPaths.mutableHeadExtensionRoot(newInventory.getObjectRootPath()),
                    e);
        }

        var upgradeOcflVersion = oldInventory.getType() != newInventory.getType();
        upgradeOcflSpecVersion(newInventory, objectRoot, upgradeOcflVersion);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purgeMutableHead(String objectId) {
        ensureOpen();

        LOG.info("Purge mutable HEAD on object <{}>", objectId);

        var extensionRoot = ObjectPaths.mutableHeadExtensionRoot(objectRootPath(objectId));

        try {
            storage.deleteDirectory(extensionRoot);
        } catch (RuntimeException e) {
            throw new CorruptObjectException(
                    String.format(
                            "Failed to purge mutable HEAD of object %s at %s. The version may need to be deleted manually.",
                            objectId, extensionRoot),
                    e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsObject(String objectId) {
        ensureOpen();

        var objectProps = examineObject(objectRootPath(objectId));
        var exists = objectProps.getOcflVersion() != null;

        LOG.debug("OCFL repository contains object <{}>: {}", objectId, exists);

        return exists;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String objectRootPath(String objectId) {
        ensureOpen();

        var objectRootPath = storageLayoutExtension.mapObjectId(objectId);

        LOG.debug("Object root path for object <{}>: {}", objectId, objectRootPath);

        return objectRootPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exportVersion(ObjectVersionId objectVersionId, Path outputPath) {
        ensureOpen();

        Enforce.notNull(objectVersionId.getVersionNum(), "versionNum cannot be null");

        if (!containsObject(objectVersionId.getObjectId())) {
            throw new NotFoundException(String.format(
                    "Object %s version %s was not found.",
                    objectVersionId.getObjectId(), objectVersionId.getVersionNum()));
        }

        var versionRootPath = FileUtil.pathJoinFailEmpty(
                objectRootPath(objectVersionId.getObjectId()),
                objectVersionId.getVersionNum().toString());

        LOG.debug("Copying <{}> to <{}>", versionRootPath, outputPath);

        try {
            storage.copyDirectoryOutOf(versionRootPath, outputPath);
        } catch (OcflNoSuchFileException e) {
            throw new NotFoundException(
                    String.format(
                            "Object %s version %s was not found.",
                            objectVersionId.getObjectId(), objectVersionId.getVersionNum()),
                    e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exportObject(String objectId, Path outputPath) {
        ensureOpen();

        if (!containsObject(objectId)) {
            throw new NotFoundException(String.format("Object %s was not found.", objectId));
        }

        var objectRootPath = objectRootPath(objectId);

        LOG.debug("Copying <{}> to <{}>", objectRootPath, outputPath);

        try {
            storage.copyDirectoryOutOf(objectRootPath, outputPath);
        } catch (OcflNoSuchFileException e) {
            throw new NotFoundException(String.format("Object %s was not found.", objectId), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void importObject(String objectId, Path objectPath) {
        ensureOpen();

        var objectRootPath = objectRootPath(objectId);

        LOG.debug("Importing <{}> to <{}>", objectId, objectRootPath);

        storage.createDirectories(FileUtil.parentPath(objectRootPath));

        try {
            storage.moveDirectoryInto(objectPath, objectRootPath);
        } catch (OcflFileAlreadyExistsException e) {
            throw new ObjectOutOfSyncException(
                    String.format("Cannot import object %s because the object already exists.", objectId));
        } catch (RuntimeException e) {
            try {
                purgeObject(objectId);
            } catch (RuntimeException e1) {
                LOG.error("Failed to rollback object {} import", objectId, e1);
            }
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResults validateObject(String objectId, boolean contentFixityCheck) {
        ensureOpen();

        if (!containsObject(objectId)) {
            throw new NotFoundException(String.format("Object %s was not found.", objectId));
        }

        var objectRoot = objectRootPath(objectId);

        LOG.debug("Validating object <{}> at <{}>", objectId, objectRoot);

        return validator.validateObject(objectRoot, contentFixityCheck);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        LOG.debug("Closing {}", this.getClass().getName());
        super.close();
        storage.close();
    }

    @Override
    protected RepositoryConfig doInitialize(OcflVersion ocflVersion, OcflExtensionConfig layoutConfig) {
        var result = this.initializer.initializeStorage(ocflVersion, layoutConfig, supportEvaluator);
        this.storageLayoutExtension = result.getStorageLayoutExtension();
        return result;
    }

    private void storeNewImmutableVersion(Inventory inventory, Path stagingDir, boolean upgradeOcflVersion) {
        var objectRoot = ObjectPaths.objectRoot(inventory);

        ensureNoMutableHead(inventory.getId(), objectRoot.path());

        var versionPath = objectVersionPath(inventory, inventory.getHead());
        var isFirstVersion = isFirstVersion(inventory);

        try {
            if (isFirstVersion) {
                storage.createDirectories(objectRoot.path());
                writeObjectNamasteFile(inventory.getType().getOcflVersion(), objectRoot.path());
            }

            moveToVersionDirectory(inventory, stagingDir, versionPath);

            try {
                verifyPriorInventory(inventory, objectRoot.inventorySidecar());
                copyInventoryToRootWithRollback(inventory, versionPath);
            } catch (RuntimeException e) {
                try {
                    storage.deleteDirectory(versionPath);
                } catch (RuntimeException e1) {
                    LOG.error(
                            "Failed to rollback the creation of object {} version {}. The object may be corrupted.",
                            inventory.getId(),
                            inventory.getHead(),
                            e1);
                }
                throw e;
            }
        } catch (RuntimeException e) {
            // TODO this could corrupt the object if another process is concurrently creating the same object
            if (isFirstVersion) {
                try {
                    purgeObject(inventory.getId());
                } catch (RuntimeException e1) {
                    LOG.error("Failed to rollback object {} creation", inventory.getId(), e1);
                }
            }
            throw e;
        }

        upgradeOcflSpecVersion(inventory, objectRoot, upgradeOcflVersion);
    }

    private void storeNewMutableHeadVersion(Inventory inventory, Path stagingDir) {
        var objectRoot = ObjectPaths.objectRoot(inventory);
        var destinationDir = objectRoot.headVersion().contentRoot().headRevisionPath();

        var isNewMutableHead = false;

        if (hasMutableHead(inventory.getObjectRootPath())) {
            ensureRootObjectHasNotChanged(inventory);
        } else {
            copyRootInventorySidecarToMutableHead(objectRoot);
            isNewMutableHead = true;
        }

        String revisionMarker = null;

        try {
            revisionMarker = createRevisionMarker(inventory, objectRoot);

            moveToRevisionDirectory(inventory, objectRoot, stagingDir, destinationDir);

            try {
                verifyPriorInventoryMutable(inventory, objectRoot, isNewMutableHead);
                // TODO if this fails the inventory may be left in a bad state
                storeMutableHeadInventory(inventory, objectRoot, stagingDir);
            } catch (RuntimeException e) {
                try {
                    storage.deleteDirectory(destinationDir);
                } catch (RuntimeException e1) {
                    LOG.error(
                            "Failed to rollback the creation of object {} version {} revision {}. The object may be corrupted.",
                            inventory.getId(),
                            inventory.getHead(),
                            inventory.getRevisionNum(),
                            e1);
                }
                throw e;
            }
        } catch (RuntimeException e) {
            if (isNewMutableHead) {
                try {
                    storage.deleteDirectory(objectRoot.mutableHeadExtensionPath());
                } catch (RuntimeException e1) {
                    LOG.error(
                            "Failed to rollback the creation of a new mutable HEAD in object {}",
                            inventory.getId(),
                            e1);
                }
            } else if (revisionMarker != null) {
                try {
                    storage.deleteFile(revisionMarker);
                } catch (RuntimeException e1) {
                    LOG.error("Failed to rollback mutable HEAD revision marker in object {}", inventory.getId(), e1);
                }
            }
            throw e;
        }

        try {
            deleteMutableHeadFilesNotInManifest(inventory, objectRoot);
        } catch (RuntimeException e) {
            LOG.error("Failed to cleanup outdated mutable HEAD content files in object {}", inventory.getId(), e);
        }

        try {
            storage.deleteEmptyDirsDown(objectRoot.headVersion().contentPath());
        } catch (RuntimeException e) {
            LOG.error("Failed to cleanup empty mutable HEAD content directories in object {}", inventory.getId(), e);
        }

        try {
            cleanupOldRevisionMarkers(inventory, objectRoot);
        } catch (RuntimeException e) {
            LOG.error("Failed to cleanup old revision markers in object {}", inventory.getId(), e);
        }
    }

    private void moveToRevisionDirectory(
            Inventory inventory, ObjectPaths.ObjectRoot objectRoot, Path stagingDir, String destination) {
        storage.createDirectories(objectRoot.headVersion().contentPath());
        try {
            storage.moveDirectoryInto(
                    stagingDir
                            .resolve(inventory.resolveContentDirectory())
                            .resolve(inventory.getRevisionNum().toString()),
                    destination);
        } catch (OcflFileAlreadyExistsException e) {
            throw new ObjectOutOfSyncException(String.format(
                    "Failed to update mutable HEAD of object %s. Changes are out of sync with the current object state.",
                    inventory.getId()));
        }
    }

    private void moveToVersionDirectory(Inventory inventory, Path stagingDir, String destination) {
        try {
            storage.moveDirectoryInto(stagingDir, destination);
        } catch (OcflFileAlreadyExistsException e) {
            throw new ObjectOutOfSyncException(String.format(
                    "Failed to create a new version of object %s. Changes are out of sync with the current object state.",
                    inventory.getId()));
        }
    }

    private void moveMutableHeadToVersionDirectory(Inventory inventory, String destination) {
        try {
            storage.moveDirectoryInternal(
                    ObjectPaths.mutableHeadVersionPath(inventory.getObjectRootPath()), destination);
        } catch (OcflFileAlreadyExistsException e) {
            throw new ObjectOutOfSyncException(String.format(
                    "Failed to create a new version of object %s. Changes are out of sync with the current object state.",
                    inventory.getId()));
        }
    }

    private void rollbackMutableHeadVersionInstall(
            Inventory inventory, ObjectPaths.ObjectRoot objectRoot, String versionPath) {
        try {
            storage.moveDirectoryInternal(versionPath, objectRoot.headVersionPath());
        } catch (RuntimeException e) {
            LOG.error(
                    "Failed to rollback new version installation in object {} at {}. It must be cleaned up manually.",
                    inventory.getId(),
                    inventory.getHead(),
                    e);
        }
    }

    private void storeMutableHeadInventory(Inventory inventory, ObjectPaths.ObjectRoot objectRoot, Path sourcePath) {
        Failsafe.with(invRetry).run(() -> {
            storage.copyFileInto(
                    ObjectPaths.inventoryPath(sourcePath),
                    objectRoot.headVersion().inventoryFile(),
                    MEDIA_TYPE_JSON);
            storage.copyFileInto(
                    ObjectPaths.inventorySidecarPath(sourcePath, inventory),
                    objectRoot.headVersion().inventorySidecar(),
                    MEDIA_TYPE_TEXT);
        });
    }

    private void copyInventoryToRootWithRollback(
            Inventory inventory, ObjectPaths.ObjectRoot objectRoot, Path stagingDir) {
        try {
            Failsafe.with(invRetry).run(() -> {
                storage.copyFileInto(
                        ObjectPaths.inventoryPath(stagingDir), objectRoot.inventoryFile(), MEDIA_TYPE_JSON);
                storage.copyFileInto(
                        ObjectPaths.inventorySidecarPath(stagingDir, inventory),
                        objectRoot.inventorySidecar(),
                        MEDIA_TYPE_TEXT);
            });
        } catch (RuntimeException e) {
            if (!isFirstVersion(inventory)) {
                rollbackInventory(inventory);
            }
            throw e;
        }
    }

    private void copyInventoryToRootWithRollback(Inventory inventory, String versionPath) {
        try {
            copyInventoryInternal(inventory, versionPath, inventory.getObjectRootPath());
        } catch (RuntimeException e) {
            rollbackInventory(inventory);
            throw e;
        }
    }

    private void rollbackInventory(Inventory inventory) {
        if (!isFirstVersion(inventory)) {
            try {
                // TODO this does not work if there was an algorithm change
                var previousVersionPath =
                        objectVersionPath(inventory, inventory.getHead().previousVersionNum());
                copyInventoryInternal(inventory, previousVersionPath, inventory.getObjectRootPath());
            } catch (RuntimeException e) {
                LOG.error(
                        "Failed to rollback inventory at {} in object {}. Object must be fixed manually.",
                        ObjectPaths.inventoryPath(inventory.getObjectRootPath()),
                        inventory.getId(),
                        e);
            }
        }
    }

    private void copyInventoryInternal(Inventory inventory, String sourcePath, String destinationPath) {
        Failsafe.with(invRetry).run(() -> {
            storage.copyFileInternal(ObjectPaths.inventoryPath(sourcePath), ObjectPaths.inventoryPath(destinationPath));
            storage.copyFileInternal(
                    ObjectPaths.inventorySidecarPath(sourcePath, inventory),
                    ObjectPaths.inventorySidecarPath(destinationPath, inventory));
        });
    }

    private void copyRootInventorySidecarToMutableHead(ObjectPaths.ObjectRoot objectRoot) {
        var rootSidecarPath = objectRoot.inventorySidecar();
        var sidecarName = rootSidecarPath.substring(rootSidecarPath.lastIndexOf('/') + 1);
        var destination = FileUtil.pathJoinFailEmpty(objectRoot.mutableHeadExtensionPath(), "root-" + sidecarName);
        storage.createDirectories(objectRoot.mutableHeadExtensionPath());
        storage.copyFileInternal(rootSidecarPath, destination);
    }

    private void verifyPriorInventoryMutable(
            Inventory inventory, ObjectPaths.ObjectRoot objectRoot, boolean isNewMutableHead) {
        String sidecarPath;

        if (isNewMutableHead) {
            sidecarPath = objectRoot.inventorySidecar();
        } else {
            sidecarPath = objectRoot.headVersion().inventorySidecar();
        }

        verifyPriorInventory(inventory, sidecarPath);
    }

    private void verifyPriorInventory(Inventory inventory, String sidecarPath) {
        if (inventory.getPreviousDigest() != null) {
            var actualDigest = getDigestFromSidecar(sidecarPath);
            if (!actualDigest.equalsIgnoreCase(inventory.getPreviousDigest())) {
                throw new ObjectOutOfSyncException(String.format(
                        "Cannot update object %s because the update is out of sync with the current object state. "
                                + "The digest of the current inventory is %s, but the digest %s was expected.",
                        inventory.getId(), actualDigest, inventory.getPreviousDigest()));
            }
        } else if (!inventory.getHead().equals(VersionNum.V1)) {
            LOG.debug("Cannot verify prior inventory for object {} because its digest is unknown.", inventory.getId());
        }
    }

    private Inventory parseAndVerifyInventory(String objectId, DigestAlgorithm digestAlgorithm, String objectRootPath) {
        var inventoryPath = ObjectPaths.inventoryPath(objectRootPath);

        try (var stream = storage.read(inventoryPath)) {
            var inventory = inventoryMapper.read(objectRootPath, digestAlgorithm, stream);

            if (verifyInventoryDigest) {
                var expectedDigest = getDigestFromSidecar(ObjectPaths.inventorySidecarPath(objectRootPath, inventory));
                if (!expectedDigest.equalsIgnoreCase(inventory.getInventoryDigest())) {
                    throw new CorruptObjectException(String.format("Invalid root inventory in object %s", objectId));
                }
            }

            return inventory;
        } catch (OcflNoSuchFileException e) {
            throw new CorruptObjectException(String.format("Object %s is missing its root inventory", objectId), e);
        } catch (IOException e) {
            throw new OcflIOException(e);
        }
    }

    private Inventory parseAndVerifyMutableInventory(
            String objectId, DigestAlgorithm digestAlgorithm, String objectRootPath) {
        var inventoryPath = ObjectPaths.mutableHeadInventoryPath(objectRootPath);

        try (var stream = storage.read(inventoryPath)) {
            var revisionNum = identifyLatestRevision(objectRootPath);
            var inventory = inventoryMapper.readMutableHead(objectRootPath, revisionNum, digestAlgorithm, stream);

            if (verifyInventoryDigest) {
                var expectedDigest =
                        getDigestFromSidecar(ObjectPaths.mutableHeadInventorySidecarPath(objectRootPath, inventory));
                if (!expectedDigest.equalsIgnoreCase(inventory.getInventoryDigest())) {
                    throw new CorruptObjectException(
                            String.format("Invalid mutable HEAD inventory in object %s", objectId));
                }
            }

            return inventory;
        } catch (OcflNoSuchFileException e) {
            throw new CorruptObjectException(
                    String.format("Object %s is missing its mutable HEAD inventory", objectId), e);
        } catch (IOException e) {
            throw new OcflIOException(e);
        }
    }

    private Inventory parseInventory(String objectRootPath) {
        var inventoryPath = ObjectPaths.inventoryPath(objectRootPath);
        try (var stream = storage.read(inventoryPath)) {
            return inventoryMapper.readNoDigest(objectRootPath, stream);
        } catch (IOException e) {
            throw new OcflIOException(e);
        }
    }

    private String createRevisionMarker(Inventory inventory, ObjectPaths.ObjectRoot objectRoot) {
        var revision = inventory.getRevisionNum().toString();
        var revisionsDir = objectRoot.mutableHeadRevisionsPath();
        var revisionPath = FileUtil.pathJoinFailEmpty(revisionsDir, revision);

        storage.createDirectories(revisionsDir);
        try {
            storage.write(revisionPath, revision.getBytes(StandardCharsets.UTF_8), MEDIA_TYPE_TEXT);
            return revisionPath;
        } catch (OcflFileAlreadyExistsException e) {
            throw new ObjectOutOfSyncException(String.format(
                    "Failed to update mutable HEAD of object %s. Changes are out of sync with the current object state.",
                    inventory.getId()));
        }
    }

    /**
     * Deletes all of the revision markers except for the marker for the current revision
     *
     * @param inventory
     * @param objectRoot
     */
    private void cleanupOldRevisionMarkers(Inventory inventory, ObjectPaths.ObjectRoot objectRoot) {
        var currentRevision = inventory.getRevisionNum();
        var revisionsDir = objectRoot.mutableHeadRevisionsPath();
        var revisions = storage.listDirectory(revisionsDir);

        var toDelete = revisions.stream()
                .filter(Listing::isFile)
                .filter(revision ->
                        RevisionNum.fromString(revision.getRelativePath()).compareTo(currentRevision) < 0)
                .map(revision -> FileUtil.pathJoinFailEmpty(revisionsDir, revision.getRelativePath()))
                .collect(Collectors.toList());

        storage.deleteFiles(toDelete);
    }

    private RevisionNum identifyLatestRevision(String objectRootPath) {
        var revisionsPath = ObjectPaths.mutableHeadRevisionsPath(objectRootPath);
        List<Listing> revisions;

        try {
            revisions = storage.listDirectory(revisionsPath);
        } catch (OcflNoSuchFileException e) {
            throw new CorruptObjectException(
                    "Object has a mutable head, but has not specified any revision numbers.", e);
        }

        var result = revisions.stream()
                .filter(Listing::isFile)
                .map(Listing::getRelativePath)
                .filter(RevisionNum::isRevisionNum)
                .map(RevisionNum::fromString)
                .max(Comparator.naturalOrder());

        if (result.isEmpty()) {
            throw new CorruptObjectException("Object has a mutable head, but has not specified any revision numbers.");
        }

        return result.get();
    }

    private void deleteMutableHeadFilesNotInManifest(Inventory inventory, ObjectPaths.ObjectRoot objectRoot) {
        List<Listing> files;

        try {
            files = storage.listRecursive(objectRoot.headVersion().contentPath());
        } catch (OcflNoSuchFileException e) {
            files = Collections.emptyList();
        }

        var prefix = FileUtil.pathJoinFailEmpty(
                OcflConstants.MUTABLE_HEAD_VERSION_PATH, inventory.resolveContentDirectory());

        var toDelete = files.stream()
                .filter(Listing::isFile)
                .map(file -> FileUtil.pathJoinFailEmpty(prefix, file.getRelativePath()))
                .filter(contentPath -> inventory.getFileId(contentPath) == null)
                .map(file -> FileUtil.pathJoinFailEmpty(inventory.getObjectRootPath(), file))
                .collect(Collectors.toList());

        storage.deleteFiles(toDelete);
    }

    private Stream<String> findOcflObjectRootDirs() {
        var iterator = storage.iterateObjects();
        try {
            var spliterator = Spliterators.spliteratorUnknownSize(
                    iterator, Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.DISTINCT);
            return StreamSupport.stream(spliterator, false).onClose(iterator::close);
        } catch (RuntimeException e) {
            iterator.close();
            throw e;
        }
    }

    private void ensureNoMutableHead(String objectId, String objectRootPath) {
        if (hasMutableHead(objectRootPath)) {
            throw new OcflStateException(String.format(
                    "Cannot create a new version of object %s because it has an active mutable HEAD.", objectId));
        }
    }

    private boolean hasMutableHead(String objectRootPath) {
        return storage.fileExists(ObjectPaths.inventoryPath(ObjectPaths.mutableHeadVersionPath(objectRootPath)));
    }

    private void ensureRootObjectHasNotChanged(Inventory inventory) {
        var savedDigest = getDigestFromSidecar(FileUtil.pathJoinFailEmpty(
                ObjectPaths.mutableHeadExtensionRoot(inventory.getObjectRootPath()),
                "root-" + INVENTORY_SIDECAR_PREFIX
                        + inventory.getDigestAlgorithm().getOcflName()));
        var rootDigest =
                getDigestFromSidecar(ObjectPaths.inventorySidecarPath(inventory.getObjectRootPath(), inventory));

        if (!savedDigest.equalsIgnoreCase(rootDigest)) {
            throw new ObjectOutOfSyncException(String.format(
                    "The mutable HEAD of object %s is out of sync with the root object state.", inventory.getId()));
        }
    }

    private String getDigestFromSidecar(String sidecarPath) {
        try {
            var sidecarContents = storage.readToString(sidecarPath);
            var parts = WHITESPACE.split(sidecarContents);
            if (parts.length == 0) {
                throw new CorruptObjectException("Invalid inventory sidecar file: " + sidecarPath);
            }
            return parts[0];
        } catch (OcflNoSuchFileException e) {
            throw new CorruptObjectException("Missing inventory sidecar: " + sidecarPath, e);
        }
    }

    private String objectVersionPath(Inventory inventory, VersionNum versionNum) {
        return FileUtil.pathJoinFailEmpty(inventory.getObjectRootPath(), versionNum.toString());
    }

    private boolean isFirstVersion(Inventory inventory) {
        return inventory.getVersions().size() == 1;
    }

    private void writeObjectNamasteFile(OcflVersion ocflVersion, String objectRootPath) {
        var namasteFile = new NamasteTypeFile(ocflVersion.getOcflObjectVersion());
        var namastePath = FileUtil.pathJoinFailEmpty(objectRootPath, namasteFile.fileName());
        storage.write(namastePath, namasteFile.fileContent().getBytes(StandardCharsets.UTF_8), MEDIA_TYPE_TEXT);
    }

    private void upgradeOcflSpecVersion(
            Inventory inventory, ObjectPaths.ObjectRoot objectRoot, boolean upgradeOcflVersion) {
        if (upgradeOcflVersion) {
            LOG.info(
                    "Upgrading object {} to OCFL spec version {}",
                    inventory.getId(),
                    inventory.getType().getOcflVersion().getRawVersion());

            try {
                var objectProps = examineObject(objectRoot.path());
                var namasteFile =
                        new NamasteTypeFile(objectProps.getOcflVersion().getOcflObjectVersion()).fileName();
                var namasteFullPath = FileUtil.pathJoinFailEmpty(objectRoot.path(), namasteFile);
                writeObjectNamasteFile(inventory.getType().getOcflVersion(), objectRoot.path());
                storage.deleteFile(namasteFullPath);
            } catch (RuntimeException e) {
                LOG.error(
                        "Failed to upgrade object {} to OCFL spec version {}. Manual intervention may be necessary.",
                        inventory.getId(),
                        inventory.getType().getOcflVersion().getRawVersion());
            }
        }
    }

    private ObjectProperties examineObject(String objectRootPath) {
        var properties = new ObjectProperties();
        List<Listing> files;

        try {
            files = storage.listDirectory(objectRootPath);
        } catch (OcflNoSuchFileException e) {
            return properties;
        }

        for (var file : files) {
            if (file.isFile()) {
                if (file.getRelativePath().startsWith(OcflConstants.INVENTORY_SIDECAR_PREFIX)) {
                    properties.setDigestAlgorithm(SidecarMapper.getDigestAlgorithmFromSidecar(file.getRelativePath()));
                } else if (file.getRelativePath().startsWith(OcflConstants.OBJECT_NAMASTE_PREFIX)) {
                    properties.setOcflVersion(OcflVersion.fromOcflObjectVersionFilename(file.getRelativePath()));
                }
            } else if (file.isDirectory()) {
                if (OcflConstants.EXTENSIONS_DIR.equals(file.getRelativePath())) {
                    properties.setExtensions(true);
                }
            }
            if (properties.getOcflVersion() != null
                    && properties.getDigestAlgorithm() != null
                    && properties.hasExtensions()) {
                break;
            }
        }

        return properties;
    }

    /**
     * @return a set of supported extension names
     */
    private Set<String> loadObjectExtensions(String objectRoot) {
        // Currently, this just ensures that the object does not use any extensions that ocfl-java does not support
        try {
            return storage.listDirectory(ObjectPaths.extensionsPath(objectRoot)).stream()
                    .filter(Listing::isDirectory)
                    .map(Listing::getRelativePath)
                    .filter(supportEvaluator::checkSupport)
                    .collect(Collectors.toSet());
        } catch (OcflNoSuchFileException e) {
            return Collections.emptySet();
        }
    }
}
