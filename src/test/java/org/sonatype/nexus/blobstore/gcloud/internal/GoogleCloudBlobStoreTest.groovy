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

import java.nio.channels.FileChannel
import java.util.stream.Collectors
import java.util.stream.Stream

import org.sonatype.nexus.blobstore.BlobIdLocationResolver
import org.sonatype.nexus.blobstore.api.Blob
import org.sonatype.nexus.blobstore.api.BlobId
import org.sonatype.nexus.blobstore.api.BlobStore
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration
import org.sonatype.nexus.blobstore.api.BlobStoreException
import org.sonatype.nexus.common.log.DryRunPrefix

import com.google.api.gax.paging.Page
import com.google.cloud.datastore.Datastore
import com.google.cloud.datastore.DatastoreException
import com.google.cloud.datastore.Entity
import com.google.cloud.datastore.KeyFactory
import com.google.cloud.datastore.QueryResults
import com.google.cloud.storage.Bucket
import com.google.cloud.storage.Storage
import com.google.cloud.storage.Storage.BlobListOption
import org.apache.commons.io.IOUtils
import spock.lang.Specification

import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.CONTENT_SIZE_ATTRIBUTE
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.CREATION_TIME_ATTRIBUTE
import static org.sonatype.nexus.blobstore.gcloud.internal.GoogleCloudBlobStore.METADATA_FILENAME

class GoogleCloudBlobStoreTest
  extends Specification
{

  GoogleCloudStorageFactory storageFactory = Mock()

  BlobIdLocationResolver blobIdLocationResolver =  Mock()

  GoogleCloudBlobStoreMetricsStore metricsStore = Mock()

  Storage storage = Mock()

  Bucket bucket = Mock()

  GoogleCloudDatastoreFactory datastoreFactory = Mock()

  Datastore datastore = Mock()

  KeyFactory keyFactory = new KeyFactory("testing")

  def blobHeaders = [
      (BlobStore.BLOB_NAME_HEADER): 'test',
      (BlobStore.CREATED_BY_HEADER): 'admin'
  ]
  GoogleCloudBlobStore blobStore = new GoogleCloudBlobStore(
      storageFactory, blobIdLocationResolver, metricsStore, datastoreFactory, new DryRunPrefix("TEST "))

  def config = new BlobStoreConfiguration()

  static File tempFileBytes
  static File tempFileAttributes
  static File fileMetadata
  static File otherMetadata

  def setupSpec() {
    tempFileBytes = File.createTempFile('gcloudtest', 'bytes')
    tempFileBytes << 'some blob contents'

    tempFileAttributes = File.createTempFile('gcloudtest', 'properties')
    tempFileAttributes << """\
        |#Thu Jun 01 23:10:55 UTC 2017
        |@BlobStore.created-by=admin
        |size=11
        |@Bucket.repo-name=test
        |creationTime=1496358655289
        |@BlobStore.content-type=text/plain
        |@BlobStore.blob-name=existing
        |sha1=eb4c2a5a1c04ca2d504c5e57e1f88cef08c75707
      """.stripMargin()

    fileMetadata = File.createTempFile('filemetadata', 'properties')
    fileMetadata << 'type=file/1'

    otherMetadata = File.createTempFile('othermetadata', 'properties')
    otherMetadata << 'type=other/2'
  }

  def cleanupSpec() {
    tempFileBytes.delete()
  }

  def setup() {
    blobIdLocationResolver.getLocation(_) >> { args -> args[0].toString() }
    blobIdLocationResolver.fromHeaders(_) >> new BlobId(UUID.randomUUID().toString())
    storageFactory.create(_) >> storage
    config.attributes = [ 'google cloud storage': [ bucket: 'mybucket', use_datastore: 'false'] ]

    datastoreFactory.create(_) >> datastore
    keyFactory.setKind('GoogleCloudBlobStoreTest')
    datastore.newKeyFactory() >> keyFactory

    datastore.put(_) >> mockEntity()
  }

  def 'initialize successfully from existing bucket'() {
    given: 'bucket exists'
      storage.get('mybucket') >> bucket

    when: 'init is called'
      blobStore.init(config)

    then: 'no attempt to create'
      0 * storage.create(!null)
  }

  def 'initialize successfully creating bucket'() {
    given: 'bucket does not exist'
      storage.get('mybucket') >> null

    when: 'init is called'
      blobStore.init(config)

    then: 'no attempt to create'
      1 * storage.create(!null)
  }

  def 'start defaults to using datastore for blob attributes'() {
    when: 'start is called with no data present'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.start()

    then: 'using datastore is true'
      blobStore.isUsingDatastore()
  }

  def 'start uses bucket for blob attributes when file metadata.properties is present'() {
    when: 'start is called with no data present'
      storage.get('mybucket') >> bucket
      pretendBlobstoreMetadataExistsAndIndicatesFileType()
      blobStore.init(config)
      blobStore.start()

    then: 'using datastore is false'
      !blobStore.isUsingDatastore()
  }

  def 'store a blob successfully'() {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()

    when: 'call create'
      Blob blob = blobStore.create(new ByteArrayInputStream('hello world'.bytes), blobHeaders)

    then: 'blob stored'
      blob != null
  }

  def 'read blob inputstream'() {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      bucket.get('content/existing.bytes', _) >> mockGoogleObject(tempFileBytes)
      datastore.run(_) >> mockQueryResults(mockEntity())

    when: 'call create'
      Blob blob = blobStore.get(new BlobId('existing'))

    then: 'blob contains expected content'
      blob.getInputStream().text == 'some blob contents'
  }

  def 'expected getBlobIdStream behavior'() {
    given: 'bucket list returns a stream of blobs'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      def b1 = com.google.cloud.storage.BlobId.of('foo', 'notundercontent.txt')
      def b3 = com.google.cloud.storage.BlobId.of('foo', 'content/vol-01/chap-08/thing.bytes')
      def b5 = com.google.cloud.storage.BlobId.of('foo', 'content/vol-02/chap-09/tmp$thing.bytes')
      def page = Mock(Page)

      bucket.list(BlobListOption.prefix(GoogleCloudBlobStore.CONTENT_PREFIX)) >> page
      page.iterateAll() >>
          [ mockGoogleObject(b1)/*, mockGoogleObject(b2)*/, mockGoogleObject(b3)/*, mockGoogleObject(b4)*/,
            mockGoogleObject(b5) ]

    when: 'getBlobIdStream called'
      Stream<BlobId> stream = blobStore.getBlobIdStream()

    then: 'expected behavior'
      def list = stream.collect(Collectors.toList())
      list.size() == 1
      list.contains(new BlobId(b3.toString()))
  }

  def 'start will accept a metadata.properties originally created with file blobstore'() {
    given: 'metadata.properties comes from a file blobstore'
      storage.get('mybucket') >> bucket
      bucket.get(METADATA_FILENAME, _) >> mockGoogleObject(fileMetadata)

    when: 'doStart is called'
      blobStore.init(config)
      blobStore.doStart()

    then: 'blobstore is started'
      notThrown(IllegalStateException)
  }

  def 'start rejects a metadata.properties containing something other than file or gcp type' () {
    given: 'metadata.properties comes from some unknown blobstore'
      storage.get('mybucket') >> bucket
      storage.get('mybucket') >> bucket
      bucket.get(METADATA_FILENAME, _) >> mockGoogleObject(otherMetadata)

    when: 'doStart is called'
      blobStore.init(config)
      blobStore.doStart()

    then: 'blobstore fails to start'
      thrown(IllegalStateException)
  }

  def 'exists returns true when the blobId is present' () {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      datastore.run(_) >> mockQueryResults(mockEntity())

    when: 'call exists'
      boolean exists = blobStore.exists(new BlobId('existing'))

    then: 'returns true'
      exists
  }

  def 'exists returns false when the blobId is not present' () {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      datastore.run(_) >> mockQueryResults(null)

    when: 'call exists'
      boolean exists = blobStore.exists(new BlobId('missing'))

    then: 'returns false'
      !exists
  }

  def 'exists throws BlobStoreException when DatastoreException is thrown' () {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      datastore.run(_) >> { throw new DatastoreException(new IOException("this is a test")) }

    when: 'call exists'
      blobStore.exists(new BlobId('existing'))

    then: 'returns false'
      thrown(BlobStoreException.class)
  }

  private mockGoogleObject(File file) {
    com.google.cloud.storage.Blob blob = Mock()
    blob.reader() >> new DelegatingReadChannel(FileChannel.open(file.toPath()))
    blob.getContent() >> IOUtils.toByteArray(new FileInputStream(file))
    blob
  }

  private mockGoogleObject(com.google.cloud.storage.BlobId blobId) {
    def blob = Mock(com.google.cloud.storage.Blob)
    blob.getName() >> blobId.name
    blob.getBlobId() >> blobId
    blob
  }

  private Entity mockEntity() {
    Entity.Builder builder = Entity.newBuilder(keyFactory.newKey(UUID.randomUUID().toString()))
    builder.set(CREATION_TIME_ATTRIBUTE, '100')
      .set(CONTENT_SIZE_ATTRIBUTE, '1')

    builder.build()
  }

  private QueryResults<Entity> mockQueryResults(Entity entity) {
    QueryResults<Entity> result = Mock()
    if (entity != null) {
      result.hasNext() >> true
      result.next() >> entity
    }
    result
  }

  private void pretendBlobstoreMetadataExistsAndIndicatesFileType() {
    bucket.get(METADATA_FILENAME, _) >> mockGoogleObject(fileMetadata)
  }
}
