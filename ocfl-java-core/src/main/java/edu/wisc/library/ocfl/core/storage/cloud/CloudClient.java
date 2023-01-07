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

package edu.wisc.library.ocfl.core.storage.cloud;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Wrapper interface abstracting cloud provider clients
 */
public interface CloudClient {

    /**
     * Close any resources the client may have created. This will NOT close resources that were passed into the client.
     */
    void close();

    /**
     * The name of the bucket the OCFL repository is in.
     *
     * @return bucket name
     */
    String bucket();

    /**
     * The key prefix of all objects within the OCFL repository. This may be empty. Multiple different OCFL repositories
     * may be stored in the same bucket if they use different key prefixes.
     *
     * @return the key prefix common to all objects in the repository
     */
    String prefix();

    /**
     * Uploads a file to the destination, and returns the object key.
     *
     * @param srcPath src file
     * @param dstPath object path
     * @return object key
     */
    CloudObjectKey uploadFile(Path srcPath, String dstPath);

    /**
     * Uploads a file to the destination, and returns the object key.
     *
     * @param srcPath src file
     * @param dstPath object path
     * @param contentType the content type of the data
     * @return object key
     */
    CloudObjectKey uploadFile(Path srcPath, String dstPath, String contentType);

    /**
     * Uploads an object with byte content
     *
     * @param dstPath object path
     * @param bytes the object content
     * @param contentType the content type of the data
     * @return object key
     */
    CloudObjectKey uploadBytes(String dstPath, byte[] bytes, String contentType);

    /**
     * Copies an object from one location to another within the same bucket.
     *
     * @param srcPath source object key
     * @param dstPath destination object path
     * @return the destination key
     * @throws KeyNotFoundException when srcPath not found
     */
    CloudObjectKey copyObject(String srcPath, String dstPath);

    /**
     * Downloads an object to the local filesystem.
     *
     * @param srcPath object key
     * @param dstPath path to write the file to
     * @return the destination path
     * @throws KeyNotFoundException when srcPath not found
     */
    Path downloadFile(String srcPath, Path dstPath);

    /**
     * Downloads and object and performs a fixity check as it streams to disk.
     *
     * @param srcPath object key
     * @return stream of object content
     * @throws KeyNotFoundException when srcPath not found
     */
    InputStream downloadStream(String srcPath);

    /**
     * Downloads an object to a string. This assumes that the object is UTF-8 encoded.
     *
     * @param srcPath object key
     * @return string content of the object
     * @throws KeyNotFoundException when srcPath not found
     */
    String downloadString(String srcPath);

    /**
     * Heads the object at the specified path.
     *
     * @param path object key
     * @return head details
     * @throws KeyNotFoundException when path not found
     */
    HeadResult head(String path);

    /**
     * Lists all of the keys under a prefix. No delimiter is used.
     *
     * @param prefix the key prefix
     * @return list response
     */
    ListResult list(String prefix);

    /**
     * Lists all of the keys within a virtual directory. Only keys that fall between the specified prefix and the next
     * '/' are returned.
     *
     * @param path the key prefix to list, if it does not end in a '/' one is appended
     * @return list response
     */
    ListResult listDirectory(String path);

    /**
     * Returns true if the specified virtual directory exists.
     *
     * @param path the key prefix to list, if it does not end in a '/' one is appended
     * @return true if the directory exists
     */
    boolean directoryExists(String path);

    /**
     * Deletes all of the objects under the specified path
     *
     * @param path the path prefix to delete
     */
    void deletePath(String path);

    /**
     * Deletes all of the specified objects. If an object does not exist, nothing happens.
     *
     * @param objectKeys keys to delete
     */
    void deleteObjects(Collection<String> objectKeys);

    /**
     * Deletes all of the objects and does not throw an exception on failure
     *
     * @param objectKeys keys to delete
     */
    void safeDeleteObjects(String... objectKeys);

    /**
     * Deletes all of the objects and does not throw an exception on failure
     *
     * @param objectKeys keys to delete
     */
    void safeDeleteObjects(Collection<String> objectKeys);

    /**
     * Returns true if the storage bucket exists
     *
     * @return if the bucket exists
     */
    boolean bucketExists();
}
