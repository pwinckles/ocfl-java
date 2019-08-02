package edu.wisc.library.ocfl.api;

import java.nio.file.Path;

public interface OcflObjectUpdater {

    // TODO Path vs String?
    // TODO should there be errors if destinationPaths already exist? perhaps a force flag?

    /**
     * Adds a file or directory to the object being operated on. The destinationPath is where the file should be inserted
     * into the object relative to the object's root.
     *
     * @param sourcePath
     * @param destinationPath
     * @return
     */
    OcflObjectUpdater addPath(Path sourcePath, String destinationPath);

    // TODO writeFile(InputStream input, String destinationPath)

    /**
     * Removes a file from an object. The given path should be relative to the object's root.
     *
     * @param path
     * @return
     */
    OcflObjectUpdater removeFile(String path);

    /**
     * Renames an existing file within an object. Both paths are relative the object's root.
     *
     * @param sourcePath
     * @param destinationPath
     * @return
     */
    OcflObjectUpdater renameFile(String sourcePath, String destinationPath);

}
