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

package edu.wisc.library.ocfl.aws;

import edu.wisc.library.ocfl.core.storage.cloud.CloudObjectKey;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import software.amazon.awssdk.transfer.s3.model.FileUpload;

/**
 * Converts a FileUpload CompletionFuture into a regular Future.
 */
public class UploadFuture implements Future<CloudObjectKey> {

    private final FileUpload upload;
    private final Path srcPath;
    private final CloudObjectKey dstKey;

    public UploadFuture(FileUpload upload, Path srcPath, CloudObjectKey dstKey) {
        this.upload = upload;
        this.srcPath = srcPath;
        this.dstKey = dstKey;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return upload.completionFuture().cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
        return upload.completionFuture().isCancelled();
    }

    @Override
    public boolean isDone() {
        return upload.completionFuture().isDone();
    }

    @Override
    public CloudObjectKey get() throws InterruptedException, ExecutionException {
        try {
            upload.completionFuture().get();
        } catch (RuntimeException e) {
            throw new ExecutionException(new OcflS3Exception(
                    "Failed to upload " + srcPath + " to " + dstKey, OcflS3Util.unwrapCompletionEx(e)));
        }
        return dstKey;
    }

    @Override
    public CloudObjectKey get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            upload.completionFuture().get(timeout, unit);
        } catch (RuntimeException e) {
            throw new ExecutionException(new OcflS3Exception(
                    "Failed to upload " + srcPath + " to " + dstKey, OcflS3Util.unwrapCompletionEx(e)));
        }
        return dstKey;
    }
}
