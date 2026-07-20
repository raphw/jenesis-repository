package build.jenesis.repository.store.filesystem;

import module java.base;

/**
 * Owner-only creation for the filesystem store: directories are created {@code rwx------} and files
 * {@code rw-------}, so a blob, its upload temp and every container directory the store creates are readable
 * and writable by the owning process only - never inheriting the process umask's world-readable {@code 022}
 * default. This is the fail-closed, secure-by-default counterpart to the credential-file hardening the CLI
 * applies; the tightening rides on {@link Files#createDirectories} / {@link Files#createTempFile} through
 * {@link PosixFilePermissions#asFileAttribute} so the permissions are set atomically at creation, not widened
 * for a window and then narrowed.
 *
 * <p>An atomic rename ({@link StandardCopyOption#ATOMIC_MOVE}) preserves the file's inode and its mode, so a
 * temp created {@code rw-------} keeps those permissions once moved into place as the final blob/key - no
 * post-move re-permissioning is needed.
 *
 * <p>POSIX file permissions are unsupported on some filesystems (e.g. Windows/NTFS). There the attribute
 * arrays are empty and creation falls back to the platform default rather than crashing: the store still
 * works, it just cannot narrow permissions the underlying filesystem does not model.
 */
final class OwnerOnly {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /** {@code rwx------} for a directory the store creates; empty on a non-POSIX filesystem. */
    private static final FileAttribute<?>[] DIRECTORY = POSIX
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))}
            : new FileAttribute<?>[0];

    /** {@code rw-------} for a file the store creates; empty on a non-POSIX filesystem. */
    private static final FileAttribute<?>[] FILE = POSIX
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
            : new FileAttribute<?>[0];

    private OwnerOnly() {
    }

    /** Create {@code dir} and any missing parents {@code rwx------} (or platform default on a non-POSIX FS). */
    static Path createDirectories(Path dir) throws IOException {
        return Files.createDirectories(dir, DIRECTORY);
    }

    /** Create a temp file in {@code dir} {@code rw-------} (or platform default on a non-POSIX FS). */
    static Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
        return Files.createTempFile(dir, prefix, suffix, FILE);
    }
}
