# OCFL Java Usage

## OCFL Repository Configuration

Use the `OcflRepositoryBuilder` to construct `OcflRepository`
instances. You should only create a single `OcflRepository` instance
per OCFL repository, and you should not reuse the same
`OcflRepositoryBuilder` to create multiple `OcflRepository` instances.

The `OcflRepositoryBuilder` will initialize a new OCFL repository if
it's pointed at an empty directory, or open an existing repository if
it's pointed at an existing OCFL storage root.

Use `OcflRepositoryBuilder.build()` to construct standard OCFL
repository and `OcflRepositoryBuilder.buildMutable()` to construct an
OCFL repository that supports the [mutable HEAD extension](https://ocfl.github.io/extensions/0005-mutable-head.html).

### Required Properties

* **storage**: Sets the storage layer implementation that the OCFL
  repository should use. Use `OcflStorageBuilder.builder()` to create an
  implementation.
* **workDir**: Sets the path to the directory that is used to assemble
  OCFL versions. If you are using filesystem storage, it is critical
  that this directory is located on the same volume as the OCFL storage
  root.
* **defaultLayoutConfig**: Configures the default storage layout the
  OCFL repository uses. The storage layout is used to map OCFL object
  IDs to object root directories within the repository. The layout
  configuration must be set when creating new OCFL repositories, but is
  not required when opening an existing repository. Storage layouts must
  be defined in by an [OCFL
  extension](https://github.com/OCFL/extensions). Currently, the
  following extensions are implemented:
    * [0002-flat-direct-storage-layout](https://ocfl.github.io/extensions/0002-flat-direct-storage-layout.html): `FlatLayoutConfig`
    * [0003-hash-and-id-n-tuple-storage-layout](https://ocfl.github.io/extensions/0003-hash-and-id-n-tuple-storage-layout.html): `HashedNTupleIdEncapsulationLayoutConfig`
    * [0004-hashed-n-tuple-storage-layout](https://ocfl.github.io/extensions/0004-hashed-n-tuple-storage-layout.html): `HashedNTupleLayoutConfig`
    * [0006-flat-omit-prefix-storage-layout](https://ocfl.github.io/extensions/0006-flat-omit-prefix-storage-layout.html): `FlatOmitPrefixLayoutConfig`
    * [0007-n-tuple-omit-prefix-storage-layout](https://ocfl.github.io/extensions/0007-n-tuple-omit-prefix-storage-layout.html): `NTupleOmitPrefixStorageLayoutConfig`

### Optional Properties

* **ocflConfig**: Sets the following default values that are used for
  creating new OCFL objects: OCFL version, digest algorithm, version
  zero-padding width, and content directory. The defaults are `1.1`,
  `sha512`, `0`, and `content`.
* **verifyStaging**: Determines whether the contents of staged
  versions should be verified immediately prior to installing them.
  This is enabled by default, but can be safely disabled if you are
  concerned about performance on particularly slow filesystems.
* **prettyPrintJson**: Enables pretty print JSON in newly written
  inventory files. By default, pretty printing is disabled to reduce
  inventory file size.
* **contentPathConstraints**: Configures what file name constraints
  are enforced on OCFL content paths. By default, there are no special
  constraints applied. Used `ContentPathConstraints` for a selection of
  pre-configured defaults. You may want to apply constraints if you are
  concerned about portability between filesystems. For example,
  disallowing `:` and `\` characters.
* **logicalPathMapper**: `LogicalPathMapper` implementations are used
  to map logical paths to safe content paths. By default, logical paths
  are mapped directly to content paths without making any changes. See
  `LogicalPathMappers` for more pre-configured options, such as
  `LogicalPathMappers.percentEncodingWindowsMapper()`, which
  percent-encodes a handful of characters that are problematic on
  Windows.
* **unsupportedExtensionBehavior**: By default set to `FAIL`, which
  means that repositories and objects that contain unsupported
  extensions will not be allowed. May be set to `WARN` to all
  unsupported extensions.
* **ignoreUnsupportedExtensions**: A set of unsupported extension
  names that should be allowed either without causing a failure, if
  `unsupportedExtensionBehavior` is set to `FAIL`, or not logging, if
  set to `WARN`
* **inventoryCache**: By default, an in-memory
  [Caffeine](https://github.com/ben-manes/caffeine) cache is used to
  cache deserialized inventories.
* **objectLock**: Set the lock implementation that's used to lock
  objects for writing. By default, it is an in-memory lock with a 10
  second wait to acquire. Use `ObjectLockBuilder` construct an alternate
  lock. When more than one processes may be concurrently writing to an
  OCFL repository, a different implementation, such as `DbObjectLock`,
  should be used.
* **objectDetailsDb**: Configures a database to use to store OCFL
  object metadata. By default, this feature is not used. It is intended
  to be used when using cloud storage, and caches a copy of the most
  recent inventory file. In addition to speeding cloud operations up a
  little, it also addresses the eventual consistency problem, though
  most cloud storage, including S3, is now strongly consistent. Use
  `ObjectDetailsDatabaseBuilder` to construct an
  `ObjectDetailsDatabase`.
* **fileLockTimeoutDuration**: Configures the max amount of time to wait
  for a file lock when updating an object from multiple threads. This
  only matters if you concurrently write files to the same object, and
  can otherwise be ignored. The default timeout is 1 minute.

## Storage Implementations

### Filesystem

The basic OCFL repository implementation stores objects under an OCFL
storage root on a locally attached filesystem. The client constructs
new object versions in a work directory before attempting to move them
into the object root. Ideally, this move operation can be executed as
an atomic rename, and, as such, the work directory configured on the
`ocfl-java` client should be located on the same mount as the OCFL
storage root.

### Configuration

Use `OcflStorageBuilder.builder()` to create and configure an
`OcflStorage` instance.

* **fileSystem**: Required, path to the OCFL storage root directory.
* **verifyInventoryDigest**: Whether to verify inventory digests on
  read. Default: `true`.

**Example**

```java
var repo = new OcflRepositoryBuilder()
        .defaultLayoutConfig(new HashedTruncatedNTupleConfig())
        .storage(storage -> storage.fileSystem(repoDir))
        .workDir(workDir)
        .build();
```

### Amazon S3

The Amazon S3 storage implementation stores OCFL objects directly in
an Amazon S3 bucket. Optionally, a key prefix can be used to partition
the repository to use only a portion of its bucket, allowing you to
store multiple OCFL repositories in the same bucket or non-OCFL
content.

At the minimum, the client needs permissions to the following actions:

* `s3:PutObject`
* `s3:GetObject`
* `s3:DeleteObject`
* `s3:ListBucket`
* `s3:AbortMultipartUpload`

If it is possible that multiple applications may be writing to the
OCFL repository, then it is essential that a distributed lock is used
to ensure that only one process is updating an object at a time.
`ocfl-java` provides a builtin database based locking mechanism that
can be used for these purposes. This is configured by setting the
`objectLock` on the `OcflRepositoryBuilder` as shown in the example
below.

Additionally, another database table may be optionally used to cache
details about the objects in the repository. This allows `ocfl-java`
to retrieve object details without needing to read inventories from
S3. It also addresses the problem of eventually consistent writes.
However, Amazon S3 is now strongly consistent, so it is no longer
critical to use this feature. If you do want to use it, configure the
`objectDetailsDb` on the `OcflRepositoryBuilder` as shown in the
example below.

Currently, the only supported databases are PostgreSQL, MariaDB, and
H2. The `ocfl-java` client populates the object details database on
demand. There is no need to pre-populate it, and the table can safely
be wiped anytime.

Note, the Amazon S3 storage implementation is significantly slower
than the file system implementation. It will likely not perform well
on large files or objects with lots of files. Additionally, it does
not cache any object files locally, requiring them to be retrieved
from S3 on every access.

### S3 Transfer Manager

`ocfl-java` uses the new [S3 Transfer
Manager](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/transfer-manager.html)
to upload and download files from S3. You can configure the transfer
manager to target a specific throughput, based on the needs of your
application. Consult the official documentation for details.

However, note that it is **crucial** that you configure the transfer
manager to use the new [CRT S3
client](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/crt-based-s3-client.html).
The CRT client is **required** by the transfer manager in order to
make multipart uploads.

If you do not specify a transfer manager when constructing the
`OcflS3Client`, then it will create the default transfer manager using
the S3 client it was provided, which, again, should be a CRT client.
When you use the default transfer manager, you need to be sure to
close the `OcflRepository` when you are done with it, otherwise the
transfer manager will not be closed.

For example, you might construct the S3 client like:

``` java
S3AsyncClient.crtBuilder().build()
```

### Configuration

Use `OcflStorageBuilder.builder()` to create and configure an
`OcflStorage` instance.

* **cloud**: Required, sets the `CloudClient` implementation to use.
  For Amazon S3, use `OcflS3Client.builder()`.
* **verifyInventoryDigest**: Whether to verify inventory digests on
  read. Default: `true`.

**Example**

```java
var repo = new OcflRepositoryBuilder()
        .defaultLayoutConfig(new HashedNTupleLayoutConfig())
        .contentPathConstraints(ContentPathConstraints.cloud())
        .objectLock(lock -> lock.dataSource(dataSource))
        .objectDetailsDb(db -> db.dataSource(dataSource))
        .storage(storage -> storage
                .cloud(OcflS3Client.builder()
                        .s3Client(s3Client)
                        .bucket(name)
                        .repoPrefix(prefix)
                        .build()))
        .workDir(workDir)
        .build();
```

## Database

If you use a database backed object lock or the object details
database, then you'll need to setup a database for the client to
connect to. Currently, PostgreSQL >= 9.3, MariaDB >= 10.2, and H2
are supported. The client automatically creates the tables that
it needs.

## Usage Considerations

### Running multiple instances

If you intend to write to an OCFL repository from multiple different
instances, you should use a database based object lock rather than the
default in-memory lock. Additionally, you may want to either adjust or
disable inventory caching, or hook up a distributed cache
implementation.

### Improving write performance

If your objects have a lot of files, then you _might_ get better
performance by parallelizing file reads and writes. Parallel writes
are only supported as of `ocfl-java` 2.0.0 or later. `ocfl-java` does
not do this for you automatically, but the following is some example
code of one possible way that you could implement parallel writes
to an object:

```java
repo.updateObject(ObjectVersionId.head(objectId), versionInfo, updater -> {
    List<? extends Future<?>> futures;
    try (var files = Files.find(
            objectPath, Integer.MAX_VALUE, (file, attrs) -> attrs.isRegularFile())) {
        futures = files.map(file -> executor.submit(() -> {
                    var logical = objectPath
                            .relativize(file)
                            .toString();
                    updater.addPath(file, logical);
                }))
                .toList();
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
    futures.forEach(future -> {
        try {
            future.get();
        } catch (Exception e) {
            throw new RuntimeException("Error adding file to object " + objectId, e);
        }
    });
});
```

The key bit here is that you use an `ExecutorService` to add multiple
files to the object at the same. You would likely want to use one thread
pool per object. Additionally, note that this technique will likely
make writes _slower_ if you are not writing a lot of files.

### Inventory size

OCFL inventory files can grow quite large when an object has lots of
files and/or lots of versions. This problem is compounded by the fact
that a copy of the inventory must be persisted in every object version
directory. There are three things you can do to attempt to control
inventory bloat:

* Do not generate an excessive number of versions of an object
* Do not pretty print the inventory files (pretty printing is disabled
  by default)
* Use `sha256` instead of `sha512` for inventory content addressing.
  `sha512` is the default and the spec recommended algorithm. On some
  systems, `sha512` is faster than `sha256`, however, it requires twice
  as much space to store. If you are concerned about space, you can
  change the algorithm by setting
  `OcflRepositoryBuilder.ocflConfig(config ->
  config.setDefaultDigestAlgorithm(DigestAlgorithm.sha256))`. Note, this
  only changes the digest algorithm used for *new* OCFL objects. It is
  not possible to modify existing objects.

## Upgrading OCFL Repositories

An existing OCFL repository can be upgraded to a later OCFL spec version
by specifying the desired version when initializing the repository. For
example:

```java
var repo = new OcflRepositoryBuilder()
        .ocflConfig(config -> config.setOcflVersion(OcflVersion.OCFL_1_1)
                                    .setUpgradeObjectsOnWrite(true))
        .storage(storage -> storage.fileSystem(repoDir))
        .workDir(workDir)
        .build();
```

If the repository in the above example was an existing 1.0 repository,
then, it would be upgraded to 1.1 and all _new_ objects would be created
as 1.1 objects. Additionally, anytime an existing 1.0 object was written
to, it would be upgraded to 1.1. If `upgradeObjectsOnWrite` was set to
`false`, then existing objects would remain on version 1.0.

## APIs

### OcflRepository

See the Javadoc in `OcflRepository` for more detailed information.

* **putObject**: Stores a fully composed object in the repository. The
  object's previous state is not carried forward. Only the files that
  are present in the given path are considered to be part of the new
  version. However, the files are still dedupped against previous
  versions.
* **updateObject**: Unlike `putObject`, `updateObject` carries forward
  the most recent object state, and allows you to make one-off changes
  (adding, removing, moving, etc files) to an object without having the
  entire object on hand.
* **getObject**: There are two different `getObject` implementations.
  The first writes a complete copy of an object at a specified version
  to a directory outside of the OCFL repository. The second returns an
  object with lazy-loading references to all of the files that are part
  of the specified object version.
* **describeObject**: Returns metadata about an object and all of its
  versions.
* **describeVersion**: Returns metadata about a specific version of an
  object.
* **fileChangeHistory**: Returns the change history for a specific
  file within an object. This is useful for identifying at what point
  specific files were changed.
* **containsObject**: Indicates whether the OCFL repository contains
  an object with the given id.
* **validateObject**: Validates an object against the OCFL 1.0 spec and
  returns a list of any errors or warnings found.
* **purgeObject**: Permanently removes an object from the repository.
  The object is NOT recoverable.
* **listObjectIds**: Returns a stream containing the ids of all of the
  objects in the repository. This API may be slow.
* **exportVersion**: Copies the entire contents of an OCFL object
  version directory to a location outside of the repository.
* **exportObject**: Copies the entire contents of an OCFL object
  directory to a location outside of the repository.
* **importVersion**: Imports an OCFL object version into the
  repository.
* **importObject**: Imports an entire OCFL object into the repository.
* **close**: Closes the repository, releasing its resources.

### OcflObjectUpdater

See the Javadoc in `OcflObjectUpdater` for more detailed information.

* **addPath**: Adds a file or directory to the object.
* **writeFile**: Adds a file to the object, using an InputStream as
  the source of the file.
* **removeFile**: Removes the file at the logical path from the
  object. The file is not removed from storage and can be reinstated
  later.
* **renameFile**: Renames a file at an existing logical path to a new
  logical path.
* **reinstateFile**: Restores a file that existed in a previous
  version of the object.
* **clearVersionState**: By default, `updateObject` carries forward
  the current object state, calling `clearVersionState` clears
  everything out of the new version, so that it behaves the same as
  `putObject`.
* **addFileFixity**: Adds an entry to the object's `fixity` block.
* **clearFixityBlock**: Removes all of the entries from the object's
  `fixity` block.

### OcflOption

A number of the APIs accept optional `OcflOption` arguments.

* **OVERWRITE**: By default, `ocfl-java` will not overwrite files that
  already exist within an object. If you want to overwrite a file, you
  must specify `OcflOption.OVERWRITE` in the operation.
* **MOVE_SOURCE**: By default, `ocfl-java` copies source files into an
  internal staging directory where it builds the new object version
  before moving the version into the repository. Specifying
  `OcflOption.MOVE_SOURCE` instructs `ocfl-java` to move the source
  files into the staging directory instead of copying them.
* **NO_VALIDATION**: By default, `ocfl-java` will run validations on
  objects and versions that are imported and exported from the
  repository. This flag instructs it not to do these validations.

## Extensions

[OCFL extensions](https://github.com/OCFL/extensions) are additional
features that the community has specified that are outside of the
scope of the OCFL spec.

### Storage Layout Extensions

Storage layout extensions describe how OCFL object IDs should be mapped
to paths within the OCFL storage root. `ocfl-java` includes built-in
implementations of registered extensions, but, you can override these
implementations or add custom layout extensions.

The following is a list of currently supported storage layout
extensions:

* [0002-flat-direct-storage-layout](https://ocfl.github.io/extensions/0002-flat-direct-storage-layout.html)
    * Configuration class: `FlatLayoutConfig`
    * Implementation class: `FlatLayoutExtension`
* [0003-hash-and-id-n-tuple-storage-layout](https://ocfl.github.io/extensions/0003-hash-and-id-n-tuple-storage-layout.html)
    * Configuration class: `HashedNTupleIdEncapsulationLayoutConfig`
    * Implementation class: `HashedNTupleIdEncapsulationLayoutExtension`
* [0004-hashed-n-tuple-storage-layout](https://ocfl.github.io/extensions/0004-hashed-n-tuple-storage-layout.html)
    * Configuration class: `HashedNTupleLayoutConfig`
    * Implementation class: `HashedNTupleLayoutExtension`
* [0006-flat-omit-prefix-storage-layout](https://ocfl.github.io/extensions/0006-flat-omit-prefix-storage-layout.html)
    * Configuration class: `FlatOmitPrefixLayoutConfig`
    * Implementation class: `FlatOmitPrefixLayoutExtension`
* [0007-n-tuple-omit-prefix-storage-layout](https://ocfl.github.io/extensions/0007-n-tuple-omit-prefix-storage-layout.html)
    * Configuration class: `NTupleOmitPrefixStorageLayoutConfig`
    * Implementation class: `NTupleOmitPrefixStorageLayoutExtension`

#### Custom Storage Layout Extensions

Custom storage layout extensions are supported by implementing
`OcflStorageLayoutExtension` and `OcflExtensionConfig`. Reference the
built-in extensions for an example of what this looks like.

After defining the extension classes, the extension must be registered
with `ocfl-java` **before** initializing your OCFL repository. It
would look something like this:

``` java
OcflExtensionRegistry.register(NewLayoutExtension.EXTENSION_NAME, NewLayoutExtension.class);
var repo = new OcflRepositoryBuilder().defaultLayoutConfig(new NewExtensionConfig())...
```

If you would like `ocfl-java` to write a copy of your extension's
specification to the OCFL storage root, then include it as a Markdown
file inside the jar your extension is defined in. The file should be
at `ocfl-specs/EXTENSION_NAME.md`.

### Mutable HEAD Extension

The [mutable HEAD
extension](https://ocfl.github.io/extensions/0005-mutable-head.html)
enables an OCFL object to have a mutable HEAD version that is stored
inside of the object root. This version is **not** an official OCFL
version, and it is not recognized by clients that do not implement
this extension. This extension allows you to iteratively make changes
to an object without every change producing a new OCFL version. When
you are satisfied with the state of the object, the mutable HEAD
version should be committed, which moves it into an immutable OCFL
version that is recognized by all OCFL clients.

To enable this extension, call `OcflRepositoryBuilder.buildMutable()`.
Note, you do not need to enable the extension for reading. `ocfl-java`
will automatically read mutable HEAD versions that already exist.
However, it will not allow you to write to an object with a mutable
HEAD unless the extension is enabled.

#### APIs

See the Javadoc in `MutableOcflRepository` for more detailed
information.

* **stageChanges**: This method works the same as `updateObject`, but,
  instead of making a new version, it updates or creates a mutable HEAD
  version.
* **commitStagedChanges**: Converts a mutable HEAD version into an
  immutable OCFL version.
* **purgeStagedChanges**: Purges a mutable HEAD version without
  creating a new OCFL version.
* **hasStagedChanges**: Indicates if an object has a mutable HEAD.
