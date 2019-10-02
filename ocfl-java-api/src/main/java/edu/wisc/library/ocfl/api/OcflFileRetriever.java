package edu.wisc.library.ocfl.api;

import edu.wisc.library.ocfl.api.io.FixityCheckInputStream;

/**
 * This class is used to lazy-load object files. A new instance should be created for each file that's intended to be load.
 */
public interface OcflFileRetriever {

    /**
     * Returns a new input stream of the file's content. The caller is responsible for closing the stream.
     *
     * @return FixityCheckInputStream of the file's content
     */
    FixityCheckInputStream retrieveFile();

}
