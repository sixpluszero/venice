package com.linkedin.davinci.kafka.consumer;

import com.linkedin.davinci.config.VeniceStoreVersionConfig;


/**
 * Coordinates whether a partition should bootstrap via blob transfer before the normal Kafka subscribe path proceeds.
 */
public interface BlobTransferBootstrapController {
  /**
   * @return {@code true} if Kafka subscription should be deferred because blob transfer bootstrap was started or is
   *         still in progress for the replica; {@code false} if normal Kafka subscription should proceed now.
   */
  boolean maybeStartBlobTransferBootstrap(
      VeniceStoreVersionConfig storeVersionConfig,
      ConsumerAction consumerAction,
      PartitionConsumptionState partitionConsumptionState,
      Runnable resumeSubscription);
}
