package com.linkedin.venice.compression;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import com.github.luben.zstd.Zstd;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.utils.ByteUtils;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;


public class TestVeniceCompressor {
  private static final Logger LOGGER = LogManager.getLogger(TestVeniceCompressor.class);
  private static final long TEST_TIMEOUT = 5 * Time.MS_PER_SECOND;

  @DataProvider(name = "Params")
  public static Object[][] paramsProvider() {
    return new Object[][] { { CompressionStrategy.NO_OP, SourceDataType.DIRECT_BYTE_BUFFER, 0 },
        { CompressionStrategy.NO_OP, SourceDataType.NON_DIRECT_BYTE_BUFFER, 0 },
        { CompressionStrategy.NO_OP, SourceDataType.BYTE_ARRAY, 0 },
        { CompressionStrategy.GZIP, SourceDataType.DIRECT_BYTE_BUFFER, 0 },
        { CompressionStrategy.GZIP, SourceDataType.NON_DIRECT_BYTE_BUFFER, 0 },
        { CompressionStrategy.GZIP, SourceDataType.BYTE_ARRAY, 0 },
        { CompressionStrategy.GZIP, SourceDataType.DIRECT_BYTE_BUFFER, ByteUtils.SIZE_OF_INT },
        { CompressionStrategy.GZIP, SourceDataType.NON_DIRECT_BYTE_BUFFER, ByteUtils.SIZE_OF_INT },
        { CompressionStrategy.GZIP, SourceDataType.BYTE_ARRAY, ByteUtils.SIZE_OF_INT },
        { CompressionStrategy.ZSTD_WITH_DICT, SourceDataType.DIRECT_BYTE_BUFFER, 0 },
        { CompressionStrategy.ZSTD_WITH_DICT, SourceDataType.NON_DIRECT_BYTE_BUFFER, 0 },
        { CompressionStrategy.ZSTD_WITH_DICT, SourceDataType.BYTE_ARRAY, 0 },
        { CompressionStrategy.ZSTD_WITH_DICT, SourceDataType.DIRECT_BYTE_BUFFER, ByteUtils.SIZE_OF_INT },
        { CompressionStrategy.ZSTD_WITH_DICT, SourceDataType.NON_DIRECT_BYTE_BUFFER, ByteUtils.SIZE_OF_INT },
        { CompressionStrategy.ZSTD_WITH_DICT, SourceDataType.BYTE_ARRAY, ByteUtils.SIZE_OF_INT } };
  }

  @DataProvider(name = "CompressionStrategy")
  public static Object[] compressionStrategyProvider() {
    return new Object[] { CompressionStrategy.NO_OP, CompressionStrategy.GZIP, CompressionStrategy.ZSTD_WITH_DICT };
  }

  private VeniceCompressor getCompressor(CompressionStrategy strategy) {
    if (Objects.requireNonNull(strategy) == CompressionStrategy.ZSTD_WITH_DICT) {
      byte[] dictionary = ZstdWithDictCompressor.buildDictionaryOnSyntheticAvroData();
      return new CompressorFactory().createCompressorWithDictionary(dictionary, Zstd.maxCompressionLevel());
    }
    return new CompressorFactory().getCompressor(strategy);
  }

  @Test(dataProvider = "CompressionStrategy", timeOut = TEST_TIMEOUT)
  public void testCompressAndDecompress(CompressionStrategy strategy) throws IOException {
    try (VeniceCompressor compressor = getCompressor(strategy)) {
      byte[] inputBytes = "Hello World".getBytes();
      ByteBuffer bbWithHeader;
      int schemaId = 123;
      if (strategy == CompressionStrategy.NO_OP) {
        bbWithHeader = ByteBuffer.allocate(inputBytes.length + VeniceCompressor.SCHEMA_HEADER_LENGTH);
        bbWithHeader.putInt(schemaId).put(inputBytes);
        bbWithHeader.flip();
        bbWithHeader.position(VeniceCompressor.SCHEMA_HEADER_LENGTH);
      } else {
        bbWithHeader = compressor.compress(ByteBuffer.wrap(inputBytes), VeniceCompressor.SCHEMA_HEADER_LENGTH);
        bbWithHeader.position(0);
        bbWithHeader.putInt(schemaId);
      }

      // Prepend schema header.
      ByteBuffer decompressedWithPrependedSchemaHeader = compressor.decompressAndPrependSchemaHeader(
          bbWithHeader.array(),
          bbWithHeader.position(),
          bbWithHeader.remaining(),
          schemaId);
      decompressedWithPrependedSchemaHeader.position(0);
      Assert.assertEquals(decompressedWithPrependedSchemaHeader.getInt(), schemaId);
      byte[] outputBytes = new byte[decompressedWithPrependedSchemaHeader.remaining()];
      decompressedWithPrependedSchemaHeader.get(outputBytes, 0, decompressedWithPrependedSchemaHeader.remaining());
      Assert.assertEquals(new String(outputBytes), "Hello World");
    }
  }

  @Test(dataProvider = "Params", timeOut = TEST_TIMEOUT)
  public void runTestInternal(CompressionStrategy strategy, SourceDataType type, int frontPadding) throws IOException {
    try (VeniceCompressor compressor = getCompressor(strategy)) {
      int threadPoolSize = 16;
      int numRunnables = 1024;
      ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);
      List<Future> compressionFutures = new ArrayList<>(numRunnables);
      try {
        for (int i = 0; i < numRunnables; i++) {
          Random rd = new Random();
          Runnable runnable = () -> {
            byte[] data = new byte[50];
            rd.nextBytes(data);
            try {
              ByteBuffer dataBuffer;
              ByteBuffer deflatedData;
              ByteBuffer reinflatedData;
              switch (type) {
                case DIRECT_BYTE_BUFFER:
                  dataBuffer = ByteBuffer.allocateDirect(data.length);
                  dataBuffer.put(data);
                  dataBuffer.position(0);
                  deflatedData = compressor.compress(dataBuffer, frontPadding);
                  reinflatedData = compressor.decompress(deflatedData);
                  Assert.assertEquals(reinflatedData, dataBuffer);

                  if (!deflatedData.isDirect()) {
                    // Decompressor implementations are allowed to return a non-direct BB, but we still want to test
                    // that
                    ByteBuffer directDeflatedData = ByteBuffer.allocateDirect(deflatedData.remaining());
                    directDeflatedData.put(deflatedData);
                    directDeflatedData.position(0);
                    reinflatedData = compressor.decompress(directDeflatedData);
                    Assert.assertEquals(reinflatedData, dataBuffer);
                  }
                  break;
                case NON_DIRECT_BYTE_BUFFER:
                  dataBuffer = ByteBuffer.wrap(data);
                  deflatedData = compressor.compress(dataBuffer, frontPadding);
                  reinflatedData = compressor.decompress(deflatedData);
                  Assert.assertEquals(reinflatedData, dataBuffer);
                  break;
                case BYTE_ARRAY:
                  byte[] deflated = compressor.compress(data);
                  reinflatedData = compressor.decompress(deflated, 0, deflated.length);
                  Assert.assertEquals(reinflatedData.array(), data);
                  break;
                default: // Defensive code
                  break;
              }
            } catch (Exception e) {
              LOGGER.error(e);
              throw new RuntimeException(e);
            }
          };
          compressionFutures.add(executorService.submit(runnable));
        }
      } finally {
        executorService.shutdown();
      }

      TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, false, true, () -> {
        try {
          executorService.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Assert.fail();
        }
      });

      try {
        for (Future compressionFuture: compressionFutures) {
          compressionFuture.get();
        }
      } catch (Throwable t) {
        Assert.fail("Compression must succeed", t);
      }
    }
  }

  private enum SourceDataType {
    DIRECT_BYTE_BUFFER, NON_DIRECT_BYTE_BUFFER, BYTE_ARRAY
  }

  @Test
  public void testZSTDThrowsExceptionOnNullDictionary() {
    assertThrows(
        () -> new CompressorFactory()
            .createVersionSpecificCompressorIfNotExist(CompressionStrategy.ZSTD_WITH_DICT, "foo_v1", null));
  }

  @Test
  public void testCompressorEqual() {
    VeniceCompressor[] compressors1 = new VeniceCompressor[] { new NoopCompressor(), new GzipCompressor(),
        new ZstdWithDictCompressor("abc".getBytes(), Zstd.maxCompressionLevel()),
        new ZstdWithDictCompressor("def".getBytes(), Zstd.maxCompressionLevel()) };
    VeniceCompressor[] compressors2 = new VeniceCompressor[] { new NoopCompressor(), new GzipCompressor(),
        new ZstdWithDictCompressor("abc".getBytes(), Zstd.maxCompressionLevel()),
        new ZstdWithDictCompressor("def".getBytes(), Zstd.maxCompressionLevel()) };
    for (int i = 0; i < compressors1.length; ++i) {
      for (int j = 0; j < compressors1.length; ++j) {
        if (i == j) {
          Assert.assertEquals(compressors1[i], compressors1[j]);
        } else {
          Assert.assertNotEquals(compressors1[i], compressors1[j]);
        }
      }
    }
    for (int i = 0; i < compressors1.length; ++i) {
      for (int j = 0; j < compressors2.length; ++j) {
        if (i == j) {
          Assert.assertEquals(compressors1[i], compressors2[j]);
        } else {
          Assert.assertNotEquals(compressors1[i], compressors2[j]);
        }
      }
    }
  }

  @Test
  public void testCompressorClose() throws IOException {
    VeniceCompressor compressor = new ZstdWithDictCompressor("abc".getBytes(), Zstd.maxCompressionLevel());
    String largePayload = RandomStringUtils.randomAlphabetic(500000);
    compressor.compress(largePayload.getBytes());
    compressor.close();
    VeniceException exception =
        expectThrows(VeniceException.class, () -> compressor.compress(ByteBuffer.wrap(largePayload.getBytes()), 4));
    assertTrue(exception.getMessage().contains("has been closed"));
  }
}
