package edu.wisc.library.ocfl.core.storage.common;

import edu.wisc.library.ocfl.api.OcflFileRetriever;
import edu.wisc.library.ocfl.api.exception.OcflFileAlreadyExistsException;
import edu.wisc.library.ocfl.api.exception.OcflNoSuchFileException;
import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Abstraction over any storage implementation. Paths are represented as strings because forward-slashes MUST
 * be used as path separators. All paths into and out of implementations of this interface are required to use
 * forward-slashes to separate path parts.
 */
public interface Storage {

    /**
     * Return a list of all files and directories contained in the specified directory.
     *
     * @param directoryPath the path to the directory to list
     * @return list of children
     * @throws OcflNoSuchFileException when the directory does not exist
     */
    List<Listing> listDirectory(String directoryPath);

    /**
     * Return a list of all leaf-node descendents of the specified directory, this includes empty leaf-node directories.
     * Intermediary directories are not returned.
     *
     * @param directoryPath the path to the directory to list
     * @return list of children
     * @throws OcflNoSuchFileException when the directory does not exist
     */
    List<Listing> listRecursive(String directoryPath);

    /**
     * Return true if the specified directory contains no children
     *
     * @param directoryPath the path to the directory
     * @return true if empty
     * @throws OcflNoSuchFileException when the directory does not exist
     */
    boolean directoryIsEmpty(String directoryPath);

    /**
     * Return an iterator that iterates over every OCFL object directory in the repository.
     *
     * @return object directory iterator
     */
    OcflObjectRootDirIterator iterateObjects();

    /**
     * Indicates if the file exists
     *
     * @param filePath path to the file
     * @return true if it exists
     */
    boolean fileExists(String filePath);

    /**
     * Streams the content of the specified file
     *
     * @param filePath path to the file
     * @return input stream of file content
     * @throws OcflNoSuchFileException when the file does not exist
     */
    InputStream read(String filePath);

    /**
     * Read the contents of the specified file to a string
     *
     * @param filePath path to the file
     * @return file contents string
     * @throws OcflNoSuchFileException when the file does not exist
     */
    String readToString(String filePath);

    /**
     * Return an {@link OcflFileRetriever} that can be used to read the specified file at a later time.
     *
     * @param filePath path to the file
     * @param algorithm digest algorithm used to calculate the digest
     * @param digest expected digest of the file
     * @return lazy file reader
     */
    OcflFileRetriever readLazy(String filePath, DigestAlgorithm algorithm, String digest);

    /**
     * Write the specified content to the specified path. The file MUST NOT already exist.
     *
     * @param filePath path to the file to write
     * @param content file content
     * @param mediaType media type of the file, may be null
     * @throws OcflFileAlreadyExistsException when the file already exists
     */
    void write(String filePath, byte[] content, String mediaType);

    /**
     * Create the specified directory and any missing ancestors.
     *
     * @param path directory to create
     */
    void createDirectories(String path);

    /**
     * Recursively copy the source directory from inside this storage system to an external destination.
     *
     * @param source internal source to copy
     * @param destination external destination
     * @throws OcflNoSuchFileException when the source does not exist
     */
    void copyDirectoryOutOf(String source, Path destination);

    /**
     * Copy a file from outside this storage system to a destination inside. If the destination already exists,
     * then it will be overwritten.
     *
     * @param source file to copy
     * @param destination internal destination
     * @param mediaType media type of the file, may be null
     */
    void copyFileInto(Path source, String destination, String mediaType);

    /**
     * Copy a file from inside this storage system to another destination inside it. If the destination already exists,
     * then it will be overwritten.
     *
     * @param sourceFile internal file to copy
     * @param destinationFile internal destination
     * @throws OcflNoSuchFileException when the source does not exist
     */
    void copyFileInternal(String sourceFile, String destinationFile);

    /**
     * Move a directory from outside this storage system to a destination inside. The destination MUST NOT already exist.
     *
     * @param source external source directory
     * @param destination internal destination
     * @throws OcflFileAlreadyExistsException when the destination already exists
     */
    void moveDirectoryInto(Path source, String destination);

    /**
     * Move a directory from inside this storage system to another location inside. The destination MUST NOT already exist.
     *
     * @param source internal source directory
     * @param destination internal destination
     * @throws OcflFileAlreadyExistsException when the destination already exists
     * @throws OcflNoSuchFileException when the source does not exist
     */
    void moveDirectoryInternal(String source, String destination);

    /**
     * Recursively delete the specified directory and all of its children.
     *
     * @param path path to delete
     */
    void deleteDirectory(String path);

    /**
     * Delete the specified file
     *
     * @param path file to delete
     */
    void deleteFile(String path);

    /**
     * Delete the specified files
     *
     * @param paths files to delete
     */
    void deleteFiles(Collection<String> paths);

    /**
     * Recursively delete all empty directory descendents of the specified directory. The starting path is eligible
     * for deletion.
     *
     * @param path starting path
     */
    void deleteEmptyDirsDown(String path);

    /**
     * Recursively delete all empty directory ancestors until the first non-empty ancestor is found. The starting path
     * is eligible for deletion.
     *
     * @param path starting path
     */
    void deleteEmptyDirsUp(String path);

    /**
     * Closes any resources the storage implementation may have open.
     */
    void close();
}
