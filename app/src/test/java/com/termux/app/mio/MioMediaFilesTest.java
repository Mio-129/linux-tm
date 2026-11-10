package com.termux.app.mio;

import com.termux.shared.termux.TermuxConstants;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * Unit tests for the file discovery and path helpers of the `swset` and `so` commands.
 *
 * Note that {@link MioMediaFiles#getHomeDirectory()} always returns
 * {@link TermuxConstants#TERMUX_HOME_DIR_PATH} which does not exist on the test machine, so the
 * tests only use absolute paths and the directory that is explicitly passed.
 */
public class MioMediaFilesTest {

    /** The temporary directory the test files are created in, inside the build directory. */
    private static final File TEST_DIR = new File("build/tmp/mio-media-test");

    @Before
    public void setUp() {
        deleteRecursively(TEST_DIR);
        Assert.assertTrue(TEST_DIR.mkdirs() || TEST_DIR.isDirectory());
    }

    @After
    public void tearDown() {
        deleteRecursively(TEST_DIR);
    }

    private static File createDirectory(File parent, String... path) {
        File dir = parent;
        for (String part : path) dir = new File(dir, part);
        Assert.assertTrue("Failed to create test directory \"" + dir.getAbsolutePath() + "\"", dir.mkdirs() || dir.isDirectory());
        return dir;
    }

    private static File createFile(File dir, String name) {
        Assert.assertTrue("Failed to create test dir \"" + dir.getAbsolutePath() + "\"", dir.mkdirs() || dir.isDirectory());
        File file = new File(dir, name);
        try {
            Assert.assertTrue("Failed to create test file \"" + file.getAbsolutePath() + "\"", file.createNewFile());
        } catch (Exception e) {
            Assert.fail("Failed to create test file: " + e);
        }
        return file;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    @Test
    public void testGetExtension() {
        Assert.assertEquals("png", MioMediaFiles.getExtension("photo.PNG"));
        Assert.assertEquals("mp4", MioMediaFiles.getExtension("my.video.file.mp4"));
        Assert.assertEquals("", MioMediaFiles.getExtension("noextension"));
        Assert.assertEquals("", MioMediaFiles.getExtension("trailingdot."));
        Assert.assertEquals("", MioMediaFiles.getExtension(".hidden"));
    }

    @Test
    public void testIsMedia() {
        Assert.assertTrue(MioMediaFiles.isImage(new File("photo.png")));
        Assert.assertTrue(MioMediaFiles.isImage(new File("photo.JPEG")));
        Assert.assertTrue(MioMediaFiles.isVideo(new File("video.mp4")));
        Assert.assertTrue(MioMediaFiles.isVideo(new File("video.MKV")));
        Assert.assertTrue(MioMediaFiles.isAudio(new File("audio.mp3")));
        Assert.assertTrue(MioMediaFiles.isMedia(new File("audio.flac")));
        Assert.assertFalse(MioMediaFiles.isMedia(new File("archive.zip")));
        Assert.assertFalse(MioMediaFiles.isMedia(new File("script.sh")));
    }

    @Test
    public void testFindMediaFileWithAbsolutePath() {
        File dir = createDirectory(TEST_DIR, "absolute");
        try {
            File image = createFile(dir, "photo.png");
            File video = createFile(dir, "video.mp4");
            createFile(dir, "notes.txt");

            Assert.assertEquals(image, MioMediaFiles.findMediaFile(image.getAbsolutePath(), dir));
            Assert.assertEquals(video, MioMediaFiles.findMediaFile(video.getAbsolutePath(), dir));
            Assert.assertNull(MioMediaFiles.findMediaFile(new File(dir, "notes.txt").getAbsolutePath(), dir));
            Assert.assertNull(MioMediaFiles.findMediaFile(new File(dir, "missing.png").getAbsolutePath(), dir));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testFindMediaFileWithNameInSearchDirectory() {
        File dir = createDirectory(TEST_DIR, "byname");
        try {
            File image = createFile(dir, "photo.png");
            createFile(dir, "notes.txt");

            Assert.assertEquals(image, MioMediaFiles.findMediaFile("photo.png", dir));
            Assert.assertEquals(image, MioMediaFiles.findMediaFile("photo", dir));
            Assert.assertNull(MioMediaFiles.findMediaFile("notes.txt", dir));
            Assert.assertNull(MioMediaFiles.findMediaFile("missing.png", dir));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testFindMediaFileInSubDirectory() {
        File dir = createDirectory(TEST_DIR, "subdirs");
        try {
            File nestedVideo = createFile(createDirectory(dir, "a", "b"), "nested.mp4");

            Assert.assertEquals(nestedVideo, MioMediaFiles.findMediaFile("nested.mp4", dir));
            Assert.assertEquals(nestedVideo, MioMediaFiles.findMediaFile("nested", dir));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testFindFirstMediaFile() {
        File dir = createDirectory(TEST_DIR, "first");
        try {
            createFile(dir, "aaa.txt");
            File image = createFile(dir, "b_image.png");
            Assert.assertEquals(image, MioMediaFiles.findFirstMediaFile(dir, MioMediaFiles.MediaType.IMAGE));
            Assert.assertEquals(image, MioMediaFiles.findFirstMediaFile(dir, MioMediaFiles.MediaType.VISUAL));

            File video = createFile(dir, "c_video.mp4");
            Assert.assertEquals(video, MioMediaFiles.findFirstMediaFile(dir, MioMediaFiles.MediaType.VIDEO));

            File audio = createFile(dir, "d_audio.mp3");
            Assert.assertEquals(audio, MioMediaFiles.findFirstMediaFile(dir, MioMediaFiles.MediaType.AUDIO));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testFindSiblingAudioFile() {
        File dir = createDirectory(TEST_DIR, "sibling");
        try {
            File video = createFile(dir, "movie.mp4");
            Assert.assertNull(MioMediaFiles.findSiblingAudioFile(video));

            createFile(dir, "other.mp3");
            Assert.assertNull(MioMediaFiles.findSiblingAudioFile(video));

            File audio = createFile(dir, "movie.mp3");
            Assert.assertEquals(audio, MioMediaFiles.findSiblingAudioFile(video));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testSearchDirectoryFallsBackToHomeDirectory() {
        Assert.assertEquals(new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
            MioMediaFiles.getSearchDirectory(null));
        Assert.assertEquals(new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
            MioMediaFiles.getSearchDirectory(new File(TEST_DIR, "does-not-exist")));

        File dir = createDirectory(TEST_DIR, "existing");
        try {
            Assert.assertEquals(dir, MioMediaFiles.getSearchDirectory(dir));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testMediaTypeMatches() {
        Assert.assertTrue(MioMediaFiles.MediaType.IMAGE.matches(new File("a.png")));
        Assert.assertFalse(MioMediaFiles.MediaType.IMAGE.matches(new File("a.mp4")));
        Assert.assertTrue(MioMediaFiles.MediaType.VIDEO.matches(new File("a.mp4")));
        Assert.assertFalse(MioMediaFiles.MediaType.VIDEO.matches(new File("a.png")));
        Assert.assertTrue(MioMediaFiles.MediaType.AUDIO.matches(new File("a.mp3")));
        Assert.assertTrue(MioMediaFiles.MediaType.VISUAL.matches(new File("a.png")));
        Assert.assertTrue(MioMediaFiles.MediaType.VISUAL.matches(new File("a.mp4")));
        Assert.assertFalse(MioMediaFiles.MediaType.VISUAL.matches(new File("a.mp3")));
    }

    @Test
    public void testSupportedExtensionsAreLowercase() {
        for (String extension : MioMediaFiles.IMAGE_EXTENSIONS)
            Assert.assertEquals(extension.toLowerCase(), extension);
        for (String extension : MioMediaFiles.VIDEO_EXTENSIONS)
            Assert.assertEquals(extension.toLowerCase(), extension);
        for (String extension : MioMediaFiles.AUDIO_EXTENSIONS)
            Assert.assertEquals(extension.toLowerCase(), extension);
    }
}
