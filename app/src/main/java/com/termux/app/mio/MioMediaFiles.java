package com.termux.app.mio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Helper class with the file discovery logic of the `swset` command.
 *
 * The commands are run from `$PREFIX/bin`, but the media files are usually in the users `~`
 * directory, or the directory the user ran the command from. Since the current working directory of
 * the shell is not available to the app, the below logic is used to find the files:
 *
 * - If an absolute path or a path relative to `~` is given, then it is used.
 * - If the file exists in `~`, then it is used.
 * - Otherwise the file is searched in the directory the command was run from, which the scripts
 *   pass in the {@link MioRequest}, and then recursively (up to {@link #MAX_SCAN_DEPTH} levels
 *   deep) in it and `~`. `~` is used as the fallback if the directory is not set or does not exist.
 */
public final class MioMediaFiles {

    public static final String LOG_TAG = "MioMediaFiles";

    /** The max depth the directory is scanned for media files. */
    public static final int MAX_SCAN_DEPTH = 3;
    /** The max amount of directories scanned for media files, to prevent long scans. */
    public static final int MAX_SCAN_DIRECTORIES = 400;

    public static final List<String> IMAGE_EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif", "jfif"));
    public static final List<String> VIDEO_EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
        "mp4", "mkv", "webm", "3gp", "m4v", "mov", "ts", "flv", "avi"));
    public static final List<String> AUDIO_EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "mka"));

    private MioMediaFiles() {
    }

    /**
     * @return Returns the extension of the file in lower case, without the leading dot. Returns an
     * empty string if the file does not have an extension.
     */
    @NonNull
    public static String getExtension(@NonNull String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) return "";
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImage(@NonNull File file) {
        return IMAGE_EXTENSIONS.contains(getExtension(file.getName()));
    }

    public static boolean isVideo(@NonNull File file) {
        return VIDEO_EXTENSIONS.contains(getExtension(file.getName()));
    }

    public static boolean isAudio(@NonNull File file) {
        return AUDIO_EXTENSIONS.contains(getExtension(file.getName()));
    }

    public static boolean isMedia(@NonNull File file) {
        return isImage(file) || isVideo(file) || isAudio(file);
    }

    @NonNull
    public static File getHomeDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    /**
     * @return Returns the directory to search for media files in. If the passed directory does not
     * exist, then `~` is returned.
     */
    @NonNull
    public static File getSearchDirectory(@Nullable File directory) {
        if (directory != null && directory.isDirectory()) return directory;
        return getHomeDirectory();
    }

    /**
     * Find a media file by the name passed to the `swset` command.
     *
     * @param name The name or path of the file, like "photo.png", "~/downloads/video.mp4" or
     * "video" for a file with any of the supported media extensions.
     * @return Returns the {@link File} if found, otherwise {@code null}.
     */
    @Nullable
    public static File findMediaFile(@Nullable String name, @Nullable File searchDirectory) {
        File file = findFile(name, searchDirectory);
        if (file == null || !isMedia(file)) return null;
        return file;
    }

    /**
     * Find a file by the name passed to the `swset` command. Both absolute and `~` prefixed paths
     * are supported. If the name has no extension, then the file with any of the supported media
     * extensions is searched in the search directory.
     *
     * @param name The name or path of the file.
     * @return Returns the {@link File} if found, otherwise {@code null}.
     */
    @Nullable
    public static File findFile(@Nullable String name, @Nullable File searchDirectory) {
        if (name == null) return null;
        name = name.trim();
        if (name.isEmpty()) return null;

        // Strip quotes that the user may have left in while the shell did not
        while (name.length() >= 2 && ((name.startsWith("\"") && name.endsWith("\"")) ||
            (name.startsWith("'") && name.endsWith("'")))) {
            name = name.substring(1, name.length() - 1);
        }

        File directFile = resolvePath(name, searchDirectory);
        if (directFile != null && directFile.isFile()) return directFile;

        if (getExtension(name).isEmpty()) {
            // The name has no extension, look for a file with any of the supported media extensions
            File match = findWithMediaExtension(name, searchDirectory);
            if (match != null) return match;
        }

        return null;
    }

    /**
     * Resolve a path relative to `~` if it is not absolute, without checking if it exists.
     */
    @Nullable
    private static File resolvePath(@NonNull String name, @Nullable File searchDirectory) {
        if (name.startsWith("~/")) {
            return new File(getHomeDirectory(), name.substring(2));
        } else if (name.startsWith("~")) {
            return new File(getHomeDirectory(), name.substring(1));
        } else if (new File(name).isAbsolute()) {
            return new File(name);
        }

        File homeFile = new File(getHomeDirectory(), name);
        if (homeFile.exists()) return homeFile;

        File searchDirFile = new File(getSearchDirectory(searchDirectory), name);
        if (searchDirFile.exists()) return searchDirFile;

        // The file may be relative to the current working directory
        File match = searchFileRecursively(getSearchDirectory(searchDirectory), name, true);
        if (match != null) return match;

        if (!getSearchDirectory(searchDirectory).equals(getHomeDirectory()))
            return searchFileRecursively(getHomeDirectory(), name, true);

        return null;
    }

    /**
     * Find a media file with any of the supported extensions.
     */
    @Nullable
    private static File findWithMediaExtension(@NonNull String name, @Nullable File searchDirectory) {
        List<File> directories = new ArrayList<>();
        directories.add(getHomeDirectory());
        File explicitDirectory = getSearchDirectory(searchDirectory);
        if (!explicitDirectory.equals(getHomeDirectory())) directories.add(explicitDirectory);

        for (File directory : directories) {
            if (!directory.isDirectory()) continue;
            File match = findFileInDirectory(directory, name, true);
            if (match != null) return match;
            match = searchFileRecursively(directory, name, true);
            if (match != null) return match;
        }

        return null;
    }

    /**
     * Search for the file (with any of the media extensions if {@code mediaOnly} is {@code true})
     * in the directory and its sub directories.
     */
    @Nullable
    private static File searchFileRecursively(@Nullable File directory, @NonNull String name, boolean mediaOnly) {
        if (directory == null || !directory.isDirectory()) return null;

        List<File> pendingDirectories = new ArrayList<>();
        pendingDirectories.add(directory);
        int scannedDirectories = 0;

        for (int depth = 0; depth < MAX_SCAN_DEPTH && !pendingDirectories.isEmpty(); depth++) {
            List<File> nextDirectories = new ArrayList<>();
            for (File currentDirectory : pendingDirectories) {
                if (++scannedDirectories > MAX_SCAN_DIRECTORIES) return null;

                File match = findFileInDirectory(currentDirectory, name, mediaOnly);
                if (match != null) return match;

                File[] subDirectories = currentDirectory.listFiles(File::isDirectory);
                if (subDirectories != null) nextDirectories.addAll(Arrays.asList(subDirectories));
            }
            pendingDirectories = nextDirectories;
        }

        return null;
    }

    /**
     * Find the file in the directory, without searching sub directories.
     */
    @Nullable
    private static File findFileInDirectory(@NonNull File directory, @NonNull String name, boolean mediaOnly) {
        File directFile = new File(directory, name);
        if (directFile.isFile() && (!mediaOnly || isMedia(directFile))) return directFile;

        File[] files = directory.listFiles(File::isFile);
        if (files == null) return null;

        // Android file systems are normally case sensitive, so also match the file name while
        // ignoring the case, like `swset foto.png` for a file named `foto.PNG`
        boolean hasExtension = !getExtension(name).isEmpty();
        for (File file : files) {
            String fileName = file.getName();
            if (mediaOnly && !isMedia(file)) continue;
            if (hasExtension) {
                if (fileName.equalsIgnoreCase(name)) return file;
            } else {
                if (fileName.equalsIgnoreCase(name)) return file;
                if (fileName.length() > name.length() && fileName.charAt(name.length()) == '.' &&
                    fileName.regionMatches(true, 0, name, 0, name.length())) return file;
            }
        }

        return null;
    }

    /**
     * Find the first media file (an image, a video or an audio file) in the directory and its sub
     * directories.
     *
     * @param directory The directory to search in, `~` is used if it is {@code null}.
     * @param type The type of media to search for, valid values are {@link MediaType}.
     * @return Returns the {@link File} if found, otherwise {@code null}.
     */
    @Nullable
    public static File findFirstMediaFile(@Nullable File directory, @NonNull MediaType type) {
        if (directory == null || !directory.isDirectory()) directory = getHomeDirectory();
        if (!directory.isDirectory()) return null;

        List<File> pendingDirectories = new ArrayList<>();
        pendingDirectories.add(directory);
        int scannedDirectories = 0;

        for (int depth = 0; depth <= MAX_SCAN_DEPTH && !pendingDirectories.isEmpty(); depth++) {
            List<File> nextDirectories = new ArrayList<>();
            for (File currentDirectory : pendingDirectories) {
                if (++scannedDirectories > MAX_SCAN_DIRECTORIES) return null;

                File[] files = currentDirectory.listFiles(File::isFile);
                if (files != null) {
                    Arrays.sort(files);
                    for (File file : files) {
                        if (type.matches(file)) {
                            Logger.logDebug(LOG_TAG, "Found " + type + " file \"" + file.getAbsolutePath() + "\"");
                            return file;
                        }
                    }
                }

                File[] subDirectories = currentDirectory.listFiles(File::isDirectory);
                if (subDirectories != null) {
                    Arrays.sort(subDirectories);
                    nextDirectories.addAll(Arrays.asList(subDirectories));
                }
            }
            pendingDirectories = nextDirectories;
        }

        return null;
    }

    /**
     * Find an audio file with the same base name as the passed video file, like `video.mp3` for
     * `video.mp4`.
     *
     * @param videoFile The video file.
     * @return Returns the {@link File} if found, otherwise {@code null}.
     */
    @Nullable
    public static File findSiblingAudioFile(@NonNull File videoFile) {
        String fileName = videoFile.getName();
        int index = fileName.lastIndexOf('.');
        String baseName = index < 0 ? fileName : fileName.substring(0, index);

        File directory = videoFile.getParentFile();
        if (directory == null) return null;

        File[] files = directory.listFiles(File::isFile);
        if (files == null) return null;

        for (File file : files) {
            if (!isAudio(file)) continue;
            String name = file.getName();
            int fileIndex = name.lastIndexOf('.');
            String fileBaseName = fileIndex < 0 ? name : name.substring(0, fileIndex);
            if (fileBaseName.equals(baseName)) return file;
        }

        return null;
    }

    /** The type of media file to search for with {@link #findFirstMediaFile(File, MediaType)}. */
    public enum MediaType {
        /** An image or a video file. */
        VISUAL,
        /** An image file. */
        IMAGE,
        /** A video file. */
        VIDEO,
        /** An audio file. */
        AUDIO;

        public boolean matches(@NonNull File file) {
            switch (this) {
                case IMAGE:
                    return isImage(file);
                case VIDEO:
                    return isVideo(file);
                case AUDIO:
                    return isAudio(file);
                case VISUAL:
                default:
                    return isImage(file) || isVideo(file);
            }
        }
    }
}
