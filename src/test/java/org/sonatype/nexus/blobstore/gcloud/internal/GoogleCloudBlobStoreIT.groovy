/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2017-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore.gcloud.internal

import java.util.stream.Collectors
import java.util.stream.Stream

import org.sonatype.nexus.blobstore.BlobIdLocationResolver
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver
import org.sonatype.nexus.blobstore.PeriodicJobService
import org.sonatype.nexus.blobstore.PeriodicJobService.PeriodicJob
import org.sonatype.nexus.blobstore.api.Blob
import org.sonatype.nexus.blobstore.api.BlobAttributes
import org.sonatype.nexus.blobstore.api.BlobId
import org.sonatype.nexus.blobstore.api.BlobStore
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker
import org.sonatype.nexus.common.log.DryRunPrefix
import org.sonatype.nexus.common.node.NodeAccess

import com.google.cloud.storage.Blob.BlobSourceOption
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import static org.sonatype.nexus.blobstore.DirectPathLocationStrategy.DIRECT_PATH_PREFIX
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER

class GoogleCloudBlobStoreIT
  extends Specification
{

  static final Logger log = LoggerFactory.getLogger(GoogleCloudBlobStoreIT.class)

  static final BlobStoreConfiguration config = new BlobStoreConfiguration()

  static String bucketName = "integration-test-${UUID.randomUUID().toString()}"

  PeriodicJobService periodicJobService = Mock({
    schedule(_, _) >> new PeriodicJob() {
      @Override
      void cancel() {
      }
    }
  })

  NodeAccess nodeAccess = Mock({
    getId() >> 'integration-test'
  })

  GoogleCloudStorageFactory storageFactory = new GoogleCloudStorageFactory()

  BlobIdLocationResolver blobIdLocationResolver =  new DefaultBlobIdLocationResolver()

  GoogleCloudBlobStoreMetricsStore metricsStore

  GoogleCloudDatastoreFactory datastoreFactory = new GoogleCloudDatastoreFactory()

  GoogleCloudBlobStore blobStore

  BlobStoreUsageChecker usageChecker = Mock()

  def conditions = new PollingConditions(timeout: 5, initialDelay: 0, factor: 1)

  def setup() {
    config.name = 'GoogleCloudBlobStoreIT'
    config.attributes = [
        'name': 'GoogleCloudBlobStoreIT',
        'google cloud storage': [
            bucket: bucketName,
            credential_file: this.getClass().getResource('/gce-credentials.json').getFile(),
        ]
    ]

    log.info("Integration test using bucket ${bucketName}")

    metricsStore = new GoogleCloudBlobStoreMetricsStore(periodicJobService, nodeAccess)
    // can't start metrics store until blobstore init is done (which creates the bucket)
    blobStore = new GoogleCloudBlobStore(storageFactory, blobIdLocationResolver, metricsStore, datastoreFactory,
        new DryRunPrefix("TEST "))
    blobStore.init(config)

    blobStore.start()
    metricsStore.start()

    usageChecker.test(_, _, _) >> true
  }

  def cleanup() {
    blobStore.stop()
  }

  def cleanupSpec() {
    Storage storage = new GoogleCloudStorageFactory().create(config)
    log.debug("Tests complete, deleting files from ${bucketName}")
    // must delete all the files within the bucket before we can delete the bucket
    storage.list(bucketName,
        Storage.BlobListOption.prefix("")).iterateAll()
        .forEach({ b -> b.delete(BlobSourceOption.generationMatch()) })
    storage.delete(bucketName)
    log.info("Integration test complete, bucket ${bucketName} deleted")
  }

  def "getDirectPathBlobIdStream returns empty stream for missing prefix"() {
    given:

    when:
      Stream<?> s = blobStore.getDirectPathBlobIdStream('notpresent')

    then:
      !s.iterator().hasNext()
  }

  def "getDirectPathBlobIdStream returns expected content"() {
    given:
      Storage storage = storageFactory.create(config)
      // mimic some RHC content, which is stored as directpath blobs
      // 4 files, but only 2 blobIds (a .bytes and a .properties blob for each blobId)
      createFile(storage, "content/directpath/health-check/repo1/report.properties.bytes")
      createFile(storage, "content/directpath/health-check/repo1/report.properties.properties")
      createFile(storage, "content/directpath/health-check/repo1/details/bootstrap.min.css.properties")
      createFile(storage, "content/directpath/health-check/repo1/details/bootstrap.min.css.bytes")

    when:
     Stream<BlobId> stream = blobStore.getDirectPathBlobIdStream('health-check/repo1')

    then:
      List<BlobId> results = stream.collect(Collectors.toList())
      results.size() == 2
      results.contains(new BlobId("${DIRECT_PATH_PREFIX}health-check/repo1/report.properties"))
      results.contains(new BlobId("${DIRECT_PATH_PREFIX}health-check/repo1/details/bootstrap.min.css"))
  }

  def "undelete successfully makes blob accessible"() {
    given:
      Blob blob = blobStore.create(new ByteArrayInputStream('hello'.getBytes()),
          [ (BlobStore.BLOB_NAME_HEADER): 'foo',
            (BlobStore.CREATED_BY_HEADER): 'someuser' ] )
      assert blob != null
      assert blobStore.delete(blob.id, 'testing')
      BlobAttributes deletedAttributes = blobStore.getBlobAttributes(blob.id)
      assert deletedAttributes.deleted
      assert deletedAttributes.deletedReason == 'testing'

    when:
      !blobStore.undelete(usageChecker, blob.id, deletedAttributes, false)

    then:
      conditions.eventually {
        Blob after = blobStore.get(blob.id)
        after != null
        BlobAttributes attributesAfter = blobStore.getBlobAttributes(blob.id)
        !attributesAfter.deleted
      }
  }

  def "undelete does nothing when dry run is true"() {
    given:
      Blob blob = blobStore.create(new ByteArrayInputStream('hello'.getBytes()),
          [ (BlobStore.BLOB_NAME_HEADER): 'foo',
            (BlobStore.CREATED_BY_HEADER): 'someuser' ] )
      assert blob != null
      BlobAttributes attributes
      conditions.eventually {
        attributes = blobStore.getBlobAttributes(blob.id)
        assert attributes != null
      }
      assert blobStore.delete(blob.id, 'testing')
      BlobAttributes deletedAttributes = blobStore.getBlobAttributes(blob.id)
      assert deletedAttributes.deleted

    when:
      blobStore.undelete(usageChecker, blob.id, attributes, true)

    then:
      Blob after = blobStore.get(blob.id)
      after == null
      BlobAttributes attributesAfter = blobStore.getBlobAttributes(blob.id)
      attributesAfter.deleted
  }

  def "undelete does nothing on non-existent blob"() {
    given:
      Properties properties = new Properties()
      properties.setProperty(HEADER_PREFIX + BLOB_NAME_HEADER, 'nonexistent')
      BlobAttributes attributes = Mock()
      attributes.deleted >> false
      attributes.properties >> properties

    expect:
      !blobStore.undelete(usageChecker, new BlobId("nonexistent"), attributes, false)
  }

  def createFile(Storage storage, String path) {
    storage.create(BlobInfo.newBuilder(bucketName, path).build(),
      "content".bytes)
  }
}
