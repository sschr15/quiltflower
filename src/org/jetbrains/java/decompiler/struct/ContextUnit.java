// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.struct;

import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.struct.lazy.LazyLoader;
import org.jetbrains.java.decompiler.struct.lazy.LazyLoader.Link;
import org.jetbrains.java.decompiler.util.DataInputFullStream;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

public class ContextUnit {

  public static final int TYPE_FOLDER = 0;
  public static final int TYPE_JAR = 1;
  public static final int TYPE_ZIP = 2;

  private final int type;
  private final boolean own;

  private final String archivePath;  // relative path to jar/zip
  private final String filename;     // folder: relative path, archive: file name
  private final IResultSaver resultSaver;
  private final IDecompiledData decompiledData;

  private final List<String> classEntries = new ArrayList<>();  // class file or jar/zip entry
  private final List<String> dirEntries = new ArrayList<>();
  private final List<String[]> otherEntries = new ArrayList<>();

  private List<StructClass> classes = new ArrayList<>();
  private Manifest manifest;

  public ContextUnit(int type, String archivePath, String filename, boolean own, IResultSaver resultSaver, IDecompiledData decompiledData) {
    this.type = type;
    this.own = own;
    this.archivePath = archivePath;
    this.filename = filename;
    this.resultSaver = resultSaver;
    this.decompiledData = decompiledData;
  }

  public void addClass(StructClass cl, String entryName) {
    classes.add(cl);
    classEntries.add(entryName);
  }

  public void addDirEntry(String entry) {
    dirEntries.add(entry);
  }

  public void addOtherEntry(String fullPath, String entry) {
    if ("fernflower_abstract_parameter_names.txt".equals(entry)) {
      byte[] data;
      try {
        if (type == TYPE_JAR || type == TYPE_ZIP) {
          try (ZipFile archive = new ZipFile(fullPath)) {
            data = InterpreterUtil.getBytes(archive, archive.getEntry(entry));
          }
        } else {
          data = InterpreterUtil.getBytes(new File(fullPath));
        }
        DecompilerContext.getStructContext().loadAbstractMetadata(new String(data, StandardCharsets.UTF_8));
      }
      catch (IOException e) {
        String message = "Cannot read fernflower_abstract_parameter_names.txt from " + fullPath;
        DecompilerContext.getLogger().writeMessage(message, e);
      }
      return;
    }
    if (DecompilerContext.getOption(IFernflowerPreferences.SKIP_EXTRA_FILES))
        return;
    otherEntries.add(new String[]{fullPath, entry});
  }

  public void reload(LazyLoader loader) throws IOException {
    List<StructClass> lstClasses = new ArrayList<>();

    for (StructClass cl : classes) {
      String oldName = cl.qualifiedName;

      StructClass newCl;
      try (DataInputFullStream in = loader.getClassStream(oldName)) {
        newCl = StructClass.create(in, cl.isOwn(), loader);
      }

      lstClasses.add(newCl);

      Link lnk = loader.getClassLink(oldName);
      loader.removeClassLink(oldName);
      loader.addClassLink(newCl.qualifiedName, lnk);
    }

    classes = lstClasses;
  }

  public void save() {
    switch (type) {
      case TYPE_FOLDER:
        // create folder
        resultSaver.saveFolder(filename);

        // non-class files
        for (String[] pair : otherEntries) {
          resultSaver.copyFile(pair[0], filename, pair[1]);
        }

        // classes
        for (int i = 0; i < classes.size(); i++) {
          StructClass cl = classes.get(i);
          if (!cl.isOwn()) {
            continue;
          }
          String entryName = decompiledData.getClassEntryName(cl, classEntries.get(i));
          if (entryName != null) {
            String content = decompiledData.getClassContent(cl);
            if (content != null) {
              int[] mapping = null;
              if (DecompilerContext.getOption(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING)) {
                mapping = DecompilerContext.getBytecodeSourceMapper().getOriginalLinesMapping();
              }
              resultSaver.saveClassFile(filename, cl.qualifiedName, entryName, content, mapping);
            }
          }
        }

        break;

      case TYPE_JAR:
      case TYPE_ZIP:
        // create archive file
        resultSaver.saveFolder(archivePath);
        resultSaver.createArchive(archivePath, filename, manifest);

        // directory entries
        for (String dirEntry : dirEntries) {
          resultSaver.saveDirEntry(archivePath, filename, dirEntry);
        }

        // non-class entries
        for (String[] pair : otherEntries) {
          if (type != TYPE_JAR || !JarFile.MANIFEST_NAME.equalsIgnoreCase(pair[1])) {
            resultSaver.copyEntry(pair[0], archivePath, filename, pair[1]);
          }
        }

        final List<Future<?>> futures = new LinkedList<>();
        final ExecutorService decompileExecutor = Executors.newFixedThreadPool(Integer.parseInt((String) DecompilerContext.getProperty(IFernflowerPreferences.THREADS)));
        final DecompilerContext rootContext = DecompilerContext.getCurrentContext();

        // classes
        for (int i = 0; i < classes.size(); i++) {
          StructClass cl = classes.get(i);
          String entryName = decompiledData.getClassEntryName(cl, classEntries.get(i));
          if (entryName != null) {
            futures.add(decompileExecutor.submit(() -> {
              setContext(rootContext);
              String content = decompiledData.getClassContent(cl);
              int[] mapping = null;
              if (DecompilerContext.getOption(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING)) {
                mapping = DecompilerContext.getBytecodeSourceMapper().getOriginalLinesMapping();
              }
              resultSaver.saveClassEntry(archivePath, filename, cl.qualifiedName, entryName, content, mapping);
            }));
          }
        }

        decompileExecutor.shutdown();

        for (Future<?> future : futures) {
          try {
            future.get();
          } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
          }
        }

        resultSaver.closeArchive(archivePath, filename);
    }
  }

  public void setContext(DecompilerContext rootContext) {
    DecompilerContext current = DecompilerContext.getCurrentContext();
    if (current == null) {
      current = new DecompilerContext(
        new HashMap<>(rootContext.properties),
        rootContext.logger,
        rootContext.structContext,
        rootContext.classProcessor,
        rootContext.poolInterceptor,
        rootContext.renamerFactory
      );
      DecompilerContext.setCurrentContext(current);
    }
  }

  public void setManifest(Manifest manifest) {
    this.manifest = manifest;
  }

  public boolean isOwn() {
    return own;
  }

  public List<StructClass> getClasses() {
    return classes;
  }
}
