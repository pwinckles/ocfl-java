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

package edu.wisc.library.ocfl.core;

import edu.wisc.library.ocfl.api.MutableOcflRepository;
import edu.wisc.library.ocfl.api.OcflConfig;
import edu.wisc.library.ocfl.api.OcflObjectUpdater;
import edu.wisc.library.ocfl.api.model.ObjectVersionId;
import edu.wisc.library.ocfl.api.model.VersionInfo;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.inventory.InventoryMapper;
import edu.wisc.library.ocfl.core.inventory.MutableHeadInventoryCommitter;
import edu.wisc.library.ocfl.core.lock.ObjectLock;
import edu.wisc.library.ocfl.core.model.Inventory;
import edu.wisc.library.ocfl.core.model.Version;
import edu.wisc.library.ocfl.core.path.constraint.ContentPathConstraintProcessor;
import edu.wisc.library.ocfl.core.path.mapper.LogicalPathMapper;
import edu.wisc.library.ocfl.core.storage.OcflStorage;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.UncheckedFiles;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extends the OCFL repository to support OCFL objects with mutable HEADs as defined by the
 * <a href="https://ocfl.github.io/extensions/0005-mutable-head.html">Mutable HEAD Extension</a>.
 * This is not supported in the official spec.
 *
 * @see OcflRepositoryBuilder
 */
// TODO This type of hierarchy is not sustainable if there are more types of OCFL extensions. Refactor when other
// extensions exist
public class DefaultMutableOcflRepository extends DefaultOcflRepository implements MutableOcflRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMutableOcflRepository.class);

    private final OcflConfig config;
    private final Duration fileLockTimeoutDuration;
    private final OcflStorage storage;
    private final Path workDir;
    private final ObjectLock objectLock;

    /**
     * @see OcflRepositoryBuilder
     *
     * @param storage storage layer
     * @param workDir path to the directory to use for assembling ocfl versions
     * @param objectLock locking client
     * @param inventoryMapper object mapper for serializing inventories
     * @param logicalPathMapper logical path mapper
     * @param contentPathConstraintProcessor content path constraint processor
     * @param config ocfl defaults configuration
     * @param verifyStaging true if the contents of a stage version should be double-checked
     * @param fileLockTimeoutDuration the max amount of time to wait for a file lock
     */
    public DefaultMutableOcflRepository(
            OcflStorage storage,
            Path workDir,
            ObjectLock objectLock,
            InventoryMapper inventoryMapper,
            LogicalPathMapper logicalPathMapper,
            ContentPathConstraintProcessor contentPathConstraintProcessor,
            OcflConfig config,
            boolean verifyStaging,
            Duration fileLockTimeoutDuration) {
        super(
                storage,
                workDir,
                objectLock,
                inventoryMapper,
                logicalPathMapper,
                contentPathConstraintProcessor,
                config,
                verifyStaging,
                fileLockTimeoutDuration);
        this.storage = Enforce.notNull(storage, "storage cannot be null");
        this.workDir = Enforce.notNull(workDir, "workDir cannot be null");
        this.objectLock = Enforce.notNull(objectLock, "objectLock cannot be null");
        this.config = Enforce.notNull(config, "config cannot be null");
        this.fileLockTimeoutDuration =
                Enforce.notNull(fileLockTimeoutDuration, "fileLockTimeoutDuration cannot be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId stageChanges(
            ObjectVersionId objectVersionId, VersionInfo versionInfo, Consumer<OcflObjectUpdater> objectUpdater) {
        ensureOpen();

        Enforce.notNull(objectVersionId, "objectVersionId cannot be null");
        Enforce.notNull(objectUpdater, "objectUpdater cannot be null");
        validateVersionInfo(versionInfo);

        LOG.debug("Stage changes to object <{}>", objectVersionId.getObjectId());

        var inventory = loadInventory(objectVersionId);

        if (inventory == null) {
            // Note: If the mutable HEAD creation fails, the object with the empty version remains
            inventory = createAndPersistEmptyVersion(objectVersionId);
        }

        enforceObjectVersionForUpdate(objectVersionId, inventory);

        var stagingDir = createStagingDir(objectVersionId.getObjectId());
        var contentDir = UncheckedFiles.createDirectories(resolveRevisionDir(inventory, stagingDir))
                .getParent();

        var inventoryUpdater = inventoryUpdaterBuilder.buildCopyStateMutable(inventory);
        var fileLocker = new FileLocker(fileLockTimeoutDuration);
        var addFileProcessor =
                addFileProcessorBuilder.build(inventoryUpdater, fileLocker, contentDir, inventory.getDigestAlgorithm());
        var updater =
                new DefaultOcflObjectUpdater(inventory, inventoryUpdater, contentDir, addFileProcessor, fileLocker);

        try {
            objectUpdater.accept(updater);
            var newInventory = buildNewInventory(inventoryUpdater, versionInfo);
            writeNewVersion(newInventory, stagingDir, false);
            return ObjectVersionId.version(objectVersionId.getObjectId(), newInventory.getHead());
        } finally {
            FileUtil.safeDeleteDirectory(stagingDir);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectVersionId commitStagedChanges(String objectId, VersionInfo versionInfo) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");
        validateVersionInfo(versionInfo);

        LOG.debug("Commit staged changes on object <{}>", objectId);

        var inventory = requireInventory(ObjectVersionId.head(objectId));

        if (inventory.hasMutableHead()) {
            var newInventory = MutableHeadInventoryCommitter.commit(inventory, now(versionInfo), versionInfo, config);
            var stagingDir = FileUtil.createObjectTempDir(workDir, objectId);
            var finalInventory = writeInventory(newInventory, stagingDir);

            try {
                objectLock.doInWriteLock(
                        inventory.getId(), () -> storage.commitMutableHead(inventory, finalInventory, stagingDir));
            } finally {
                FileUtil.safeDeleteDirectory(stagingDir);
            }
        }

        return ObjectVersionId.version(objectId, inventory.getHead());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purgeStagedChanges(String objectId) {
        ensureOpen();

        Enforce.notBlank(objectId, "objectId cannot be blank");

        LOG.info("Purge staged changes on object <{}>", objectId);

        objectLock.doInWriteLock(objectId, () -> storage.purgeMutableHead(objectId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasStagedChanges(String objectId) {
        ensureOpen();
        Enforce.notBlank(objectId, "objectId cannot be blank");

        LOG.debug("Check if object <{}> has staged changes", objectId);

        var inventory = loadInventory(ObjectVersionId.head(objectId));

        if (inventory != null) {
            return inventory.hasMutableHead();
        }

        return false;
    }

    private Inventory createAndPersistEmptyVersion(ObjectVersionId objectId) {
        LOG.info("Creating object {} with an empty version.", objectId.getObjectId());

        var stubInventory = createStubInventory(objectId);
        var stagingDir = FileUtil.createObjectTempDir(workDir, objectId.getObjectId());
        UncheckedFiles.createDirectories(resolveContentDir(stubInventory, stagingDir));

        try {
            var inventory = stubInventory
                    .buildFrom()
                    .addHeadVersion(Version.builder()
                            .versionInfo(new VersionInfo()
                                    .setMessage("Auto-generated empty object version.")
                                    .setUser("ocfl-java", "https://github.com/OCFL/ocfl-java"))
                            .created(now(null))
                            .build())
                    .build();

            writeNewVersion(inventory, stagingDir, false);
            return inventory;
        } finally {
            FileUtil.safeDeleteDirectory(stagingDir);
        }
    }

    private Path resolveRevisionDir(Inventory inventory, Path parent) {
        var contentDir = resolveContentDir(inventory, parent);
        var newRevision = inventory.nextRevisionNum();
        return contentDir.resolve(newRevision.toString());
    }
}
