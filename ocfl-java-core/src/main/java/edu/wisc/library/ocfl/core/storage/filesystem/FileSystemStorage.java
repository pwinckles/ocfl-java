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

package edu.wisc.library.ocfl.core.storage.filesystem;

import edu.wisc.library.ocfl.api.OcflFileRetriever;
import edu.wisc.library.ocfl.api.exception.OcflFileAlreadyExistsException;
import edu.wisc.library.ocfl.api.exception.OcflIOException;
import edu.wisc.library.ocfl.api.exception.OcflNoSuchFileException;
import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.storage.common.Listing;
import edu.wisc.library.ocfl.core.storage.common.OcflObjectRootDirIterator;
import edu.wisc.library.ocfl.core.storage.common.Storage;
import edu.wisc.library.ocfl.core.util.FileUtil;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Storage abstraction over the local filesystem
 */
public class FileSystemStorage implements Storage {

    private static final Logger LOG = LoggerFactory.getLogger(FileSystemStorage.class);

    private final Path storageRoot;

    public FileSystemStorage(Path storageRoot) {
        this.storageRoot = Enforce.notNull(storageRoot, "storageRoot cannot be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Listing> listDirectory(String directoryPath) {
        var fullPath = storageRoot.resolve(directoryPath);

        try (var children = Files.list(fullPath)) {
            return children.map(child -> {
                        var name = child.getFileName().toString();
                        if (Files.isRegularFile(child)) {
                            return Listing.file(name);
                        } else if (Files.isDirectory(child)) {
                            return Listing.directory(name);
                        } else {
                            return Listing.other(name);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Listing> listRecursive(String directoryPath) {
        var fullPath = storageRoot.resolve(directoryPath);

        var listings = new ArrayList<Listing>();

        try {
            Files.walkFileTree(fullPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile()) {
                        listings.add(createListing(Listing.Type.File, file));
                    } else {
                        listings.add(createListing(Listing.Type.Other, file));
                    }
                    return super.visitFile(file, attrs);
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (FileUtil.isDirEmpty(dir) && !dir.equals(fullPath)) {
                        listings.add(createListing(Listing.Type.Directory, dir));
                    }
                    return super.postVisitDirectory(dir, exc);
                }

                private Listing createListing(Listing.Type type, Path file) {
                    var relative = FileUtil.pathToStringStandardSeparator(fullPath.relativize(file));
                    return new Listing(type, relative);
                }
            });
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }

        return listings;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean directoryIsEmpty(String directoryPath) {
        var fullPath = storageRoot.resolve(directoryPath);

        try (var children = Files.list(fullPath)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectRootDirIterator iterateObjects() {
        return new FileSystemOcflObjectRootDirIterator(storageRoot);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean fileExists(String filePath) {
        return Files.exists(storageRoot.resolve(filePath));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream read(String filePath) {
        try {
            return new BufferedInputStream(Files.newInputStream(storageRoot.resolve(filePath)));
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String readToString(String filePath) {
        try (var stream = read(filePath)) {
            return new String(stream.readAllBytes());
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflFileRetriever readLazy(String filePath, DigestAlgorithm algorithm, String digest) {
        var fullPath = storageRoot.resolve(filePath);
        return new FileSystemOcflFileRetriever(fullPath, algorithm, digest);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(String filePath, byte[] content, String mediaType) {
        var fullPath = storageRoot.resolve(filePath);

        try {
            Files.write(fullPath, content, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createDirectories(String path) {
        var fullPath = storageRoot.resolve(path);
        try {
            Files.createDirectories(fullPath);
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyDirectoryOutOf(String source, Path destination) {
        var fullPath = storageRoot.resolve(source);
        FileUtil.recursiveCopy(fullPath, destination);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyFileInto(Path source, String destination, String mediaType) {
        var dstPath = storageRoot.resolve(destination);
        try {
            Files.copy(source, dstPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyFileInternal(String sourceFile, String destinationFile) {
        var srcPath = storageRoot.resolve(sourceFile);
        var dstPath = storageRoot.resolve(destinationFile);
        try {
            Files.copy(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void moveDirectoryInto(Path source, String destination) {
        var dstPath = storageRoot.resolve(destination);
        try {
            FileUtil.moveDirectory(source, dstPath);
        } catch (FileAlreadyExistsException e) {
            throw new OcflFileAlreadyExistsException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void moveDirectoryInternal(String source, String destination) {
        var srcPath = storageRoot.resolve(source);
        var dstPath = storageRoot.resolve(destination);
        try {
            FileUtil.moveDirectory(srcPath, dstPath);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("Source must exist")) {
                throw new OcflNoSuchFileException(String.format("Directory %s does not exist", source));
            }
        } catch (FileAlreadyExistsException e) {
            throw new OcflFileAlreadyExistsException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteDirectory(String path) {
        var fullPath = storageRoot.resolve(path);
        FileUtil.deleteDirectory(fullPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteFile(String path) {
        var fullPath = storageRoot.resolve(path);
        try {
            Files.deleteIfExists(fullPath);
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteFiles(Collection<String> paths) {
        var failed = new ArrayList<>();

        for (var path : paths) {
            try {
                deleteFile(path);
            } catch (RuntimeException e) {
                LOG.warn("Failed to delete file: {}", path, e);
                failed.add(path);
            }
        }

        if (!failed.isEmpty()) {
            throw new OcflIOException(String.format("Failed to delete files: %s", failed));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteEmptyDirsDown(String path) {
        var fullPath = storageRoot.resolve(path);
        FileUtil.deleteEmptyDirs(fullPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteEmptyDirsUp(String path) {
        var fullPath = storageRoot.resolve(path);
        FileUtil.deleteDirAndParentsIfEmpty(fullPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        // no-op
    }
}
