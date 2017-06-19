package com.rnziparchive;

import android.content.res.AssetFileDescriptor;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class RNZipArchiveModule extends ReactContextBaseJavaModule {
  private static final String TAG = RNZipArchiveModule.class.getSimpleName();

  private static final int BUFFER_SIZE = 4096;
  private static final String PROGRESS_EVENT_NAME = "zipArchiveProgressEvent";
  private static final String EVENT_KEY_FILENAME = "filename";
  private static final String EVENT_KEY_PROGRESS = "progress";

  public RNZipArchiveModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return "RNZipArchive";
  }

  @ReactMethod
  public void unzip(String zipFilePath, String destDirectory, Promise promise) {
    FileInputStream inputStream;
    File file;
    try {
      inputStream = new FileInputStream(zipFilePath);
      file = new File(zipFilePath);
    } catch (FileNotFoundException | NullPointerException e) {
      promise.reject(null, "Couldn't open file " + zipFilePath + ". ");
      return;
    }
    try {
      unzipStream(zipFilePath, destDirectory, inputStream, file.length());
    } catch (Exception ex) {
      promise.reject(null, ex.getMessage());
      return;
    }
    promise.resolve(destDirectory);
  }

  @ReactMethod
  public void unzipAssets(String assetsPath, String destDirectory, Promise promise) {
    InputStream assetsInputStream;
    long size;

    try {
      assetsInputStream = getReactApplicationContext().getAssets().open(assetsPath);
      AssetFileDescriptor fileDescriptor = getReactApplicationContext().getAssets().openFd(assetsPath);
      size = fileDescriptor.getLength();
    } catch (IOException e) {
      promise.reject(null, String.format("Asset file `%s` could not be opened", assetsPath));
      return;
    }

    try {
      unzipStream(assetsPath, destDirectory, assetsInputStream, size);
    } catch (Exception ex) {
      promise.reject(null, ex.getMessage());
      return;
    }
    promise.resolve(destDirectory);
  }

  @ReactMethod
  public void zip(String fileOrDirectory, String destDirectory, Promise promise) {
    List<String> filePaths = new ArrayList<>();
    File file;
    try {
      File tmp = new File(fileOrDirectory);
      if (tmp.exists()) {
        if (tmp.isDirectory()) {
          List<File> files = getSubFiles(tmp, true);
          for (int i = 0; i < files.size(); i++) {
            filePaths.add(files.get(i).getAbsolutePath());
          }
        } else {
          filePaths.add(fileOrDirectory);
        }
      } else {
        throw new FileNotFoundException(fileOrDirectory);
      }
    } catch (FileNotFoundException | NullPointerException e) {
      promise.reject(null, "Couldn't open file/directory " + fileOrDirectory + ".");
      return;
    }

    try {
      zipStream(filePaths.toArray(new String[filePaths.size()]), destDirectory, filePaths.size());
    } catch (Exception ex) {
      promise.reject(null, ex.getMessage());
      return;
    }

    promise.resolve(destDirectory);
  }

  private void zipStream(String[] files, String destFile, long totalSize) throws Exception {
    try {
      if (destFile.contains("/")) {
        File destDir = new File(destFile.substring(0, destFile.lastIndexOf("/")));
        if (!destDir.exists()) {
          destDir.mkdirs();
        }
      }

      if (new File(destFile).exists()) {
        new File(destFile).delete();
      }

      BufferedInputStream origin = null;
      FileOutputStream dest = new FileOutputStream(destFile);

      ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(dest));

      byte data[] = new byte[BUFFER_SIZE];

      updateProgress(0, 1, destFile); // force 0%
      for (int i = 0; i < files.length; i++) {
        FileInputStream fi = new FileInputStream(files[i]);
        String filename = files[i].substring(files[i].lastIndexOf("/") + 1);
        ZipEntry entry = new ZipEntry(filename);
        out.putNextEntry(entry);
        if (!new File(files[i]).isDirectory()) {
          origin = new BufferedInputStream(fi, BUFFER_SIZE);
          int count;
          while ((count = origin.read(data, 0, BUFFER_SIZE)) != -1) {
            out.write(data, 0, count);
          }
          origin.close();
        }
      }
      updateProgress(1, 1, destFile); // force 100%
      out.close();
    } catch (Exception ex) {
      ex.printStackTrace();
      updateProgress(0, 1, destFile); // force 0%
      throw new Exception(String.format("Couldn't zip %s", destFile));
    }
  }

  private List<File> getSubFiles(File baseDir, boolean isContainFolder) {
    List<File> fileList = new ArrayList<>();
    File[] tmpList = baseDir.listFiles();
    for (File file : tmpList) {
      if (file.isFile()) {
        fileList.add(file);
      }
      if (file.isDirectory()) {
        if (isContainFolder) {
          fileList.add(file); //key code
        }
        fileList.addAll(getSubFiles(file, isContainFolder));
      }
    }
    return fileList;
  }

  private void unzipStream(String zipFilePath, String destDirectory, InputStream inputStream, long totalSize) throws Exception {
    BufferedOutputStream dest = null;
    int chunkSize = 8192;
    boolean allowStoredEntriesWithDataDescriptor = true;
    ZipArchiveInputStream zipIn = new ZipArchiveInputStream(
      new BufferedInputStream(inputStream),
      null,
      false,
      allowStoredEntriesWithDataDescriptor
    );

    ArchiveEntry entry = null;
    while ((entry = zipIn.getNextZipEntry()) != null) {
      if (!entry.isDirectory()) {
        File f = new File(destDirectory, entry.getName());
        f.getParentFile().mkdirs();
        int count;
        byte data[] = new byte[chunkSize];
        FileOutputStream out = new FileOutputStream(f);
        dest = new BufferedOutputStream(out, chunkSize);
        while ((count = zipIn.read(data, 0, chunkSize)) != -1) {
          dest.write(data, 0, count);
        }
        dest.flush();
        dest.close();
      }
    }
    zipIn.close();
  }

  private void updateProgress(long extractedBytes, long totalSize, String zipFilePath) {
    double progress = (double) extractedBytes / (double) totalSize;
    Log.d(TAG, String.format("updateProgress: %.0f%%", progress * 100));

    WritableMap map = Arguments.createMap();
    map.putString(EVENT_KEY_FILENAME, zipFilePath);
    map.putDouble(EVENT_KEY_PROGRESS, progress);
    getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(PROGRESS_EVENT_NAME, map);
  }

  /**
   * Extracts a zip entry (file entry)
   *
   * @param zipIn
   * @param filePath
   * @throws IOException
   * @return number of bytes extracted
   */
  private long extractFile(ZipInputStream zipIn, String filePath) throws IOException {
    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
    long size = 0;
    byte[] bytesIn = new byte[BUFFER_SIZE];
    int read;
    while ((read = zipIn.read(bytesIn)) != -1) {
      bos.write(bytesIn, 0, read);
      size += read;
    }
    bos.close();

    return size;
  }
}
