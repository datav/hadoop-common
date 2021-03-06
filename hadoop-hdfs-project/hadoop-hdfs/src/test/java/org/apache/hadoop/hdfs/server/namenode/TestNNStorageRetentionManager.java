/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.server.namenode.NNStorage.getFinalizedEditsFileName;
import static org.apache.hadoop.hdfs.server.namenode.NNStorage.getImageFileName;
import static org.apache.hadoop.hdfs.server.namenode.NNStorage.getInProgressEditsFileName;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.hdfs.server.namenode.FSImageStorageInspector.FSImageFile;
import org.apache.hadoop.hdfs.server.namenode.FileJournalManager.EditLogFile;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.NameNodeDirType;
import org.apache.hadoop.hdfs.server.namenode.NNStorageRetentionManager.StoragePurger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;


public class TestNNStorageRetentionManager {
  Configuration conf = new Configuration();

  /**
   * For the purpose of this test, purge as many edits as we can 
   * with no extra "safety cushion"
   */
  @Before
  public void setNoExtraEditRetention() {
    conf.setLong(DFSConfigKeys.DFS_NAMENODE_NUM_EXTRA_EDITS_RETAINED_KEY, 0);
  }
  
  /**
   * Test the "easy case" where we have more images in the
   * directory than we need to keep. Should purge the
   * old ones.
   */
  @Test
  public void testPurgeEasyCase() throws IOException {
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE_AND_EDITS);
    tc.addImage("/foo1/current/" + getImageFileName(100), true);
    tc.addImage("/foo1/current/" + getImageFileName(200), true);
    tc.addImage("/foo1/current/" + getImageFileName(300), false);
    tc.addImage("/foo1/current/" + getImageFileName(400), false);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(101,200), true);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(201,300), true);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(301,400), false);
    tc.addLog("/foo1/current/" + getInProgressEditsFileName(401), false);
    
    // Test that other files don't get purged
    tc.addLog("/foo1/current/VERSION", false);
    runTest(tc);
  }
  
  /**
   * Same as above, but across multiple directories
   */
  @Test
  public void testPurgeMultipleDirs() throws IOException {
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE_AND_EDITS);
    tc.addRoot("/foo2", NameNodeDirType.IMAGE_AND_EDITS);
    tc.addImage("/foo1/current/" + getImageFileName(100), true);
    tc.addImage("/foo1/current/" + getImageFileName(200), true);
    tc.addImage("/foo2/current/" + getImageFileName(200), true);
    tc.addImage("/foo1/current/" + getImageFileName(300), false);
    tc.addImage("/foo1/current/" + getImageFileName(400), false);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(101, 200), true);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(201, 300), true);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(201, 300), true);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(301, 400), false);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(301, 400), false);
    tc.addLog("/foo1/current/" + getInProgressEditsFileName(401), false);
    runTest(tc);
  }
  
  /**
   * Test that if we have fewer fsimages than the configured
   * retention, we don't purge any of them
   */
  @Test
  public void testPurgeLessThanRetention() throws IOException {
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE_AND_EDITS);
    tc.addImage("/foo1/current/" + getImageFileName(100), false);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(101,200), false);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(201,300), false);
    tc.addLog("/foo1/current/" + getFinalizedEditsFileName(301,400), false);
    tc.addLog("/foo1/current/" + getInProgressEditsFileName(401), false);
    runTest(tc);
  }

  /**
   * Check for edge case with no logs present at all.
   */
  @Test
  public void testNoLogs() throws IOException {
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE_AND_EDITS);
    tc.addImage("/foo1/current/" + getImageFileName(100), true);
    tc.addImage("/foo1/current/" + getImageFileName(200), true);
    tc.addImage("/foo1/current/" + getImageFileName(300), false);
    tc.addImage("/foo1/current/" + getImageFileName(400), false);
    runTest(tc);
  }
  
  /**
   * Check for edge case with no logs or images present at all.
   */
  @Test
  public void testEmptyDir() throws IOException {
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE_AND_EDITS);
    runTest(tc);
  }

  /**
   * Test that old in-progress logs are properly purged
   */
  @Test
  public void testOldInProgress() throws IOException {
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE_AND_EDITS);
    tc.addImage("/foo1/current/" + getImageFileName(100), true);
    tc.addImage("/foo1/current/" + getImageFileName(200), true);
    tc.addImage("/foo1/current/" + getImageFileName(300), false);
    tc.addImage("/foo1/current/" + getImageFileName(400), false);
    tc.addLog("/foo1/current/" + getInProgressEditsFileName(101), true);
    runTest(tc);
  }

  @Test
  public void testSeparateEditDirs() throws IOException {
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE);
    tc.addRoot("/foo2", NameNodeDirType.EDITS);
    tc.addImage("/foo1/current/" + getImageFileName(100), true);
    tc.addImage("/foo1/current/" + getImageFileName(200), true);
    tc.addImage("/foo1/current/" + getImageFileName(300), false);
    tc.addImage("/foo1/current/" + getImageFileName(400), false);

    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(101, 200), true);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(201, 300), true);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(301, 400), false);
    tc.addLog("/foo2/current/" + getInProgressEditsFileName(401), false);
    runTest(tc);    
  }
  
  @Test
  public void testRetainExtraLogs() throws IOException {
    conf.setLong(DFSConfigKeys.DFS_NAMENODE_NUM_EXTRA_EDITS_RETAINED_KEY,
        50);
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE);
    tc.addRoot("/foo2", NameNodeDirType.EDITS);
    tc.addImage("/foo1/current/" + getImageFileName(100), true);
    tc.addImage("/foo1/current/" + getImageFileName(200), true);
    tc.addImage("/foo1/current/" + getImageFileName(300), false);
    tc.addImage("/foo1/current/" + getImageFileName(400), false);

    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(101, 200), true);
    // Since we need 50 extra edits, *do* retain the 201-300 segment 
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(201, 300), false);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(301, 400), false);
    tc.addLog("/foo2/current/" + getInProgressEditsFileName(401), false);
    runTest(tc);
  }
  
  @Test
  public void testRetainExtraLogsLimitedSegments() throws IOException {
    conf.setLong(DFSConfigKeys.DFS_NAMENODE_NUM_EXTRA_EDITS_RETAINED_KEY,
        150);
    conf.setLong(DFSConfigKeys.DFS_NAMENODE_MAX_EXTRA_EDITS_SEGMENTS_RETAINED_KEY, 2);
    TestCaseDescription tc = new TestCaseDescription();
    tc.addRoot("/foo1", NameNodeDirType.IMAGE);
    tc.addRoot("/foo2", NameNodeDirType.EDITS);
    tc.addImage("/foo1/current/" + getImageFileName(100), true);
    tc.addImage("/foo1/current/" + getImageFileName(200), true);
    tc.addImage("/foo1/current/" + getImageFileName(300), false);
    tc.addImage("/foo1/current/" + getImageFileName(400), false);

    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(1, 100), true);
    // Without lowering the max segments to retain, we'd retain all segments
    // going back to txid 150 (300 - 150).
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(101, 175), true);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(176, 200), true);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(201, 225), true);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(226, 240), true);
    // Only retain 2 extra segments. The 301-400 segment is considered required,
    // not extra.
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(241, 275), false);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(276, 300), false);
    tc.addLog("/foo2/current/" + getFinalizedEditsFileName(301, 400), false);
    tc.addLog("/foo2/current/" + getInProgressEditsFileName(401), false);
    runTest(tc);
  }
  
  private void runTest(TestCaseDescription tc) throws IOException {
    StoragePurger mockPurger =
      Mockito.mock(NNStorageRetentionManager.StoragePurger.class);
    ArgumentCaptor<FSImageFile> imagesPurgedCaptor =
      ArgumentCaptor.forClass(FSImageFile.class);    
    ArgumentCaptor<EditLogFile> logsPurgedCaptor =
      ArgumentCaptor.forClass(EditLogFile.class);    

    // Ask the manager to purge files we don't need any more
    new NNStorageRetentionManager(conf,
        tc.mockStorage(), tc.mockEditLog(mockPurger), mockPurger)
      .purgeOldStorage();
    
    // Verify that it asked the purger to remove the correct files
    Mockito.verify(mockPurger, Mockito.atLeast(0))
      .purgeImage(imagesPurgedCaptor.capture());
    Mockito.verify(mockPurger, Mockito.atLeast(0))
      .purgeLog(logsPurgedCaptor.capture());

    // Check images
    Set<String> purgedPaths = Sets.newHashSet();
    for (FSImageFile purged : imagesPurgedCaptor.getAllValues()) {
      purgedPaths.add(purged.getFile().toString());
    }    
    Assert.assertEquals(Joiner.on(",").join(tc.expectedPurgedImages),
        Joiner.on(",").join(purgedPaths));

    // Check images
    purgedPaths.clear();
    for (EditLogFile purged : logsPurgedCaptor.getAllValues()) {
      purgedPaths.add(purged.getFile().toString());
    }    
    Assert.assertEquals(Joiner.on(",").join(tc.expectedPurgedLogs),
        Joiner.on(",").join(purgedPaths));
  }
  
  private static class TestCaseDescription {
    private Map<String, FakeRoot> dirRoots = Maps.newHashMap();
    private Set<String> expectedPurgedLogs = Sets.newHashSet();
    private Set<String> expectedPurgedImages = Sets.newHashSet();
    
    private static class FakeRoot {
      NameNodeDirType type;
      List<String> files;
      
      FakeRoot(NameNodeDirType type) {
        this.type = type;
        files = Lists.newArrayList();
      }

      StorageDirectory mockStorageDir() {
        return FSImageTestUtil.mockStorageDirectory(
            type, false,
            files.toArray(new String[0]));
      }
    }

    void addRoot(String root, NameNodeDirType dir) {
      dirRoots.put(root, new FakeRoot(dir));
    }

    private void addFile(String path) {
      for (Map.Entry<String, FakeRoot> entry : dirRoots.entrySet()) {
        if (path.startsWith(entry.getKey())) {
          entry.getValue().files.add(path);
        }
      }
    }
    
    void addLog(String path, boolean expectPurge) {
      addFile(path);
      if (expectPurge) {
        expectedPurgedLogs.add(path);
      }
    }
    
    void addImage(String path, boolean expectPurge) {
      addFile(path);
      if (expectPurge) {
        expectedPurgedImages.add(path);
      }
    }
    
    NNStorage mockStorage() throws IOException {
      List<StorageDirectory> sds = Lists.newArrayList();
      for (FakeRoot root : dirRoots.values()) {
        sds.add(root.mockStorageDir());
      }
      return mockStorageForDirs(sds.toArray(new StorageDirectory[0]));
    }
    
    @SuppressWarnings("unchecked")
    public FSEditLog mockEditLog(StoragePurger purger) {
      final List<JournalManager> jms = Lists.newArrayList();
      final JournalSet journalSet = new JournalSet(0);
      for (FakeRoot root : dirRoots.values()) {
        if (!root.type.isOfType(NameNodeDirType.EDITS)) continue;
        
        // passing null NNStorage for unit test because it does not use it
        FileJournalManager fjm = new FileJournalManager(
            root.mockStorageDir(), null);
        fjm.purger = purger;
        jms.add(fjm);
        journalSet.add(fjm, false);
      }

      FSEditLog mockLog = Mockito.mock(FSEditLog.class);
      Mockito.doAnswer(new Answer<Void>() {

        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
          Object[] args = invocation.getArguments();
          assert args.length == 1;
          long txId = (Long) args[0];
          
          for (JournalManager jm : jms) {
            jm.purgeLogsOlderThan(txId);
          }
          return null;
        }
      }).when(mockLog).purgeLogsOlderThan(Mockito.anyLong());
      
      Mockito.doAnswer(new Answer<Void>() {
        
        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
          Object[] args = invocation.getArguments();
          journalSet.selectInputStreams((Collection<EditLogInputStream>)args[0],
              (long)((Long)args[1]), (boolean)((Boolean)args[2]));
          return null;
        }
      }).when(mockLog).selectInputStreams(Mockito.anyCollection(),
          Mockito.anyLong(), Mockito.anyBoolean());
      return mockLog;
    }
  }

  private static NNStorage mockStorageForDirs(final StorageDirectory ... mockDirs)
      throws IOException {
    NNStorage mockStorage = Mockito.mock(NNStorage.class);
    Mockito.doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        FSImageStorageInspector inspector =
          (FSImageStorageInspector) invocation.getArguments()[0];
        for (StorageDirectory sd : mockDirs) {
          inspector.inspectDirectory(sd);
        }
        return null;
      }
    }).when(mockStorage).inspectStorageDirs(
        Mockito.<FSImageStorageInspector>anyObject());
    return mockStorage;
  }
}
