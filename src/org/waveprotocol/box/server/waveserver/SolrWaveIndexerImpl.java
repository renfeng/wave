/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.waveprotocol.box.server.waveserver;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.http.HttpStatus;
import org.waveprotocol.box.common.DeltaSequence;
import org.waveprotocol.box.common.DocumentConstants;
import org.waveprotocol.wave.model.document.operation.AnnotationBoundaryMap;
import org.waveprotocol.wave.model.document.operation.Attributes;
import org.waveprotocol.wave.model.document.operation.AttributesUpdate;
import org.waveprotocol.wave.model.document.operation.DocOp;
import org.waveprotocol.wave.model.document.operation.DocOpCursor;
import org.waveprotocol.wave.model.document.operation.impl.InitializationCursorAdapter;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.ReadableBlipData;
import org.waveprotocol.wave.model.wave.data.ReadableWaveletData;
import org.waveprotocol.wave.util.logging.Log;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * @author Frank R. <renfeng.cn@gmail.com>
 */
@Singleton
public class SolrWaveIndexerImpl extends AbstractWaveIndexer implements WaveBus.Subscriber,
    PerUserWaveViewBus.Listener {

  private static final Log LOG = Log.get(SolrWaveIndexerImpl.class);

  // TODO (Yuri Z.): Inject executor.
  private static final Executor executor = Executors.newSingleThreadExecutor();

  private final ReadableWaveletDataProvider waveletDataProvider;

  /*-
   * copied with modifications from
   * org.waveprotocol.box.common.Snippets.collateTextForOps(Iterable<DocOp>)
   *
   * replaced white space character with new line
   */
  /**
   * Concatenates all of the text of the specified docops into a single String.
   * 
   * @param documentops the document operations to concatenate.
   * @return A String containing the characters from the operations.
   */
  public static String readText(ReadableBlipData doc) {

    final StringBuilder resultBuilder = new StringBuilder();

    DocOp docOp = doc.getContent().asOperation();
    docOp.apply(InitializationCursorAdapter.adapt(new DocOpCursor() {
      @Override
      public void characters(String s) {
        resultBuilder.append(s);
      }

      @Override
      public void annotationBoundary(AnnotationBoundaryMap map) {
      }

      @Override
      public void elementStart(String type, Attributes attrs) {
        if (type.equals(DocumentConstants.LINE)) {
          resultBuilder.append("\n");
        }
      }

      @Override
      public void elementEnd() {
      }

      @Override
      public void retain(int itemCount) {
      }

      @Override
      public void deleteCharacters(String chars) {
      }

      @Override
      public void deleteElementStart(String type, Attributes attrs) {
      }

      @Override
      public void deleteElementEnd() {
      }

      @Override
      public void replaceAttributes(Attributes oldAttrs, Attributes newAttrs) {
      }

      @Override
      public void updateAttributes(AttributesUpdate attrUpdate) {
      }
    }));

    return resultBuilder.toString();
  }

  @Inject
  public SolrWaveIndexerImpl(WaveMap waveMap, WaveletProvider waveletProvider,
      ReadableWaveletDataProvider waveletDataProvider,
      WaveletNotificationDispatcher notificationDispatcher) {
    super(waveMap, waveletProvider);
    this.waveletDataProvider = waveletDataProvider;
    notificationDispatcher.subscribe(this);
  }

  @Override
  public ListenableFuture<Void> onParticipantAdded(final WaveletName waveletName,
      ParticipantId participant) {
    /*
     * ignored. See waveletCommitted(WaveletName, HashedVersion)
     */
    return null;
  }

  @Override
  public ListenableFuture<Void> onParticipantRemoved(final WaveletName waveletName,
      ParticipantId participant) {
    /*
     * ignored. See waveletCommitted(WaveletName, HashedVersion)
     */
    return null;
  }

  @Override
  public ListenableFuture<Void> onWaveInit(final WaveletName waveletName) {

    ListenableFutureTask<Void> task = new ListenableFutureTask<Void>(new Callable<Void>() {

      @Override
      public Void call() throws Exception {
        ReadableWaveletData waveletData;
        try {
          waveletData = waveletDataProvider.getReadableWaveletData(waveletName);
          updateIndex(waveletData);
        } catch (WaveServerException e) {
          LOG.log(Level.SEVERE, "Failed to initialize index for " + waveletName, e);
          throw e;
        }
        return null;
      }
    });
    executor.execute(task);
    return task;
  }

  @Override
  protected void processWavelet(WaveletName waveletName) {
    onWaveInit(waveletName);
  }

  @Override
  protected void postIndexHook() {
    try {
      getWaveMap().unloadAllWavelets();
    } catch (WaveletStateException e) {
      throw new IndexException("Problem encountered while cleaning up", e);
    }
  }

  private void updateIndex(ReadableWaveletData wavelet) throws IndexException {

    Preconditions.checkNotNull(wavelet);

    /*
     * update solr index
     */

    PostMethod postMethod =
        new PostMethod(SolrSearchProviderImpl.SOLR_BASE_URL + "/update/json?commit=true");
    try {
      JsonArray docsJson = new JsonArray();

      String waveId = wavelet.getWaveId().serialise();
      String waveletId = wavelet.getWaveletId().serialise();
      String modified = Long.toString(wavelet.getLastModifiedTime());
      String creator = wavelet.getCreator().getAddress();

      for (String docName : wavelet.getDocumentIds()) {
        ReadableBlipData document = wavelet.getDocument(docName);

        /*
         * skips non-blip documents
         */
        if ("conversation".equals(docName) || "m/read".equals(docName)) {
          continue;
        }

        String text = readText(document);

        /*
         * TODO (Frank R.) index wave title for link text in solr-bot search
         * results
         */

        /*
         * (regression alert) it hangs at
         * com.google.common.collect.Iterables.cycle(T...)
         */
        // String text =
        // Snippets
        // .collateTextForOps(Iterables.cycle((DocOp)
        // document.getContent().asOperation()));

        /*
         * (regression alert) cannot reuse Snippets because it trims the
         * content.
         */
        // Iterable<DocOp> docs = Arrays.asList((DocOp)
        // document.getContent().asOperation());
        // String text = Snippets.collateTextForOps(docs);

        /*-
         * XXX (Frank R.) (experimental) skips invisible blips
         * a newly created blip starts with (and contains only)
         * a new line character, and is not treated as invisible
         */
        if (text.length() == 0) {
          continue;
        }

        JsonArray participantsJson = new JsonArray();
        for (ParticipantId participant : wavelet.getParticipants()) {
          String participantAddress = participant.toString();
          participantsJson.add(new JsonPrimitive(participantAddress));
        }

        JsonObject docJson = new JsonObject();
        docJson.addProperty(SolrSearchProviderImpl.ID, waveId + "/~/conv+root/" + docName);
        docJson.addProperty(SolrSearchProviderImpl.WAVE_ID, waveId);
        docJson.addProperty(SolrSearchProviderImpl.WAVELET_ID, waveletId);
        docJson.addProperty(SolrSearchProviderImpl.DOC_NAME, docName);
        docJson.addProperty(SolrSearchProviderImpl.LMT, modified);
        docJson.add(SolrSearchProviderImpl.WITH, participantsJson);
        docJson.add(SolrSearchProviderImpl.WITH_FUZZY, participantsJson);
        docJson.addProperty(SolrSearchProviderImpl.CREATOR, creator);
        docJson.addProperty(SolrSearchProviderImpl.TEXT, text);
        docJson.addProperty(SolrSearchProviderImpl.IN, "inbox");

        docsJson.add(docJson);
      }

      RequestEntity requestEntity =
          new StringRequestEntity(docsJson.toString(), "application/json", "UTF-8");
      postMethod.setRequestEntity(requestEntity);

      HttpClient httpClient = new HttpClient();
      int statusCode = httpClient.executeMethod(postMethod);
      if (statusCode != HttpStatus.SC_OK) {
        throw new IndexException(waveId);
      }

      // LOG.fine(postMethod.getResponseBodyAsString());

    } catch (IOException e) {
      throw new IndexException(String.valueOf(wavelet.getWaveletId()), e);
    } finally {
      postMethod.releaseConnection();
    }

    return;
  }

  @Override
  public void waveletUpdate(final ReadableWaveletData wavelet, DeltaSequence deltas) {
    /*
     * (regression alert) commented out for optimization, see
     * waveletCommitted(WaveletName, HashedVersion)
     */
    // updateIndex(wavelet);
  }

  @Override
  public void waveletCommitted(final WaveletName waveletName, final HashedVersion version) {

    Preconditions.checkNotNull(waveletName);

    /*
     * (regression alert) don't update on current thread to prevent lock error
     */
    ListenableFutureTask<Void> task = new ListenableFutureTask<Void>(new Callable<Void>() {

      @Override
      public Void call() throws Exception {
        ReadableWaveletData waveletData;
        try {
          waveletData = waveletDataProvider.getReadableWaveletData(waveletName);
          System.out.println("commit " + version + " " + waveletData.getVersion());
          if (waveletData.getVersion() == version.getVersion()) {
            updateIndex(waveletData);
          }
        } catch (WaveServerException e) {
          LOG.log(Level.SEVERE, "Failed to update index for " + waveletName, e);
          throw e;
        }
        return null;
      }
    });
    executor.execute(task);

    return;
  }

  @Override
  public synchronized void remakeIndex() throws WaveletStateException, WaveServerException {

    /*-
     * to fully rebuild the index, need to delete everything first
     * the <query> tag should contain the value of
     * org.waveprotocol.box.server.waveserver.SolrSearchProviderImpl.Q
     *
     * http://localhost:8983/solr/update?stream.body=<delete><query>waveId_s:[*%20TO%20*]%20AND%20waveletId_s:[*%20TO%20*]%20AND%20docName_s:[*%20TO%20*]%20AND%20lmt_l:[*%20TO%20*]%20AND%20with_ss:[*%20TO%20*]%20AND%20with_txt:[*%20TO%20*]%20AND%20creator_t:[*%20TO%20*]</query></delete>
     * http://localhost:8983/solr/update?stream.body=<commit/>
     *
     * see
     * http://wiki.apache.org/solr/FAQ#How_can_I_delete_all_documents_from_my_index.3F
     */

    GetMethod getMethod = new GetMethod();
    try {
      getMethod
          .setURI(new URI(SolrSearchProviderImpl.SOLR_BASE_URL + "/update?wt=json"
              + "&stream.body=<delete><query>" + SolrSearchProviderImpl.Q + "</query></delete>",
              false));

      HttpClient httpClient = new HttpClient();
      int statusCode = httpClient.executeMethod(getMethod);
      if (statusCode == HttpStatus.SC_OK) {
        getMethod.setURI(new URI(SolrSearchProviderImpl.SOLR_BASE_URL + "/update?wt=json"
            + "&stream.body=<commit/>", false));

        httpClient = new HttpClient();
        statusCode = httpClient.executeMethod(getMethod);
        if (statusCode != HttpStatus.SC_OK) {
          LOG.warning("failed to clean solr index");
        }
      } else {
        LOG.warning("failed to clean solr index");
      }
    } catch (Exception e) {
      LOG.warning("failed to clean solr index", e);
    } finally {
      getMethod.releaseConnection();
    }

    super.remakeIndex();

    return;
  }
}
