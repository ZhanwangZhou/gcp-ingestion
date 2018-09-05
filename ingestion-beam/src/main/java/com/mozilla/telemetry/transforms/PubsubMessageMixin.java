/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.mozilla.telemetry.transforms;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;

/* Required to decode PubsubMessage from json
 *
 * This is necessary because jackson can automatically determine how to encode
 * PubsubMessage as json, but it can't automatically tell how to decode it
 * because the 'getAttributeMap' method returns the value for the 'attributes'
 * parameter. Additionally jackson doesn't like that there are no setter
 * methods on PubsubMessage.
 *
 * The default jackson output format for PubsubMessage, which we want to read,
 * looks like:
 * {
 *   "payload": "<base64 encoded byte array",
 *   "attributeMap": {"<key>": "<value>"...}
 * }
 */

@JsonPropertyOrder(alphabetic = true)
public abstract class PubsubMessageMixin {
  @JsonCreator
  public PubsubMessageMixin(
      @JsonProperty("payload") byte[] payload,
      @JsonProperty("attributeMap") Map<String, String> attributes
  ) { }

  /** A Jackson ObjectMapper pre-configured to use this mixin for PubsubMessage. */
  public static ObjectMapper MAPPER = new ObjectMapper()
      .addMixIn(PubsubMessage.class, PubsubMessageMixin.class);
}
