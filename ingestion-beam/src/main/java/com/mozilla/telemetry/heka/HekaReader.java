/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.mozilla.telemetry.heka;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.mozilla.telemetry.heka.Heka.Field.ValueType;
import com.mozilla.telemetry.ingestion.core.Constant.Attribute;
import com.mozilla.telemetry.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.xerial.snappy.Snappy;

public class HekaReader {

  private static PubsubMessage readHekaMessage(InputStream is) throws IOException {
    while (true) {
      // continue reading until we find a heka message or we reach the end of the
      // file
      // FIXME: add some code to keep track of errors, parseErrors does something
      // like this
      int cursor = is.read();
      if (cursor == -1) {
        return null;
      } else if (cursor == 0x1E) {
        // found separator, continue
        break;
      }
    }

    int headerLength = is.read();
    byte[] headerBuffer = new byte[headerLength];
    is.read(headerBuffer);
    Heka.Header header = Heka.Header.parseFrom(headerBuffer);

    // Parse unit separator
    if (is.read() != 0x1F) {
      throw new IOException("Invalid Heka Frame: missing unit separator");
    }

    // get message data
    byte[] messageBuffer = new byte[header.getMessageLength()];
    is.read(messageBuffer);

    int uncompressedLength = Snappy.uncompressedLength(messageBuffer);
    byte[] uncompressedMessage = new byte[uncompressedLength];
    Snappy.uncompress(messageBuffer, 0, header.getMessageLength(), uncompressedMessage, 0);
    Heka.Message message = Heka.Message.parseFrom(uncompressedMessage);

    ObjectNode payload = Json.readObjectNode(message.getPayload().getBytes(StandardCharsets.UTF_8));

    FieldDescriptor fieldDescriptor = message.getDescriptorForType().findFieldByName("fields");
    Object field = message.getField(fieldDescriptor);
    if (field instanceof List) {
      for (Object i : (List) field) {
        Heka.Field f = (Heka.Field) i;
        String key = f.getName();
        List<String> path = key.contains(".") ? Arrays.asList(key.split("\\."))
            : Arrays.asList("meta", key);
        String lastKey = path.get(path.size() - 1);
        ObjectNode target = payload;
        for (int j = 0; j < path.size() - 1; j++) {
          String p = path.get(j);
          target = (ObjectNode) (target.has(p) ? target.path(p) : target.putObject(p));
        }
        if (f.getValueType() == Heka.Field.ValueType.STRING && target.isObject()) {
          String value = f.getValueString(0);
          if (value.charAt(0) == '{') {
            target.set(lastKey, Json.readObjectNode(value.getBytes(StandardCharsets.UTF_8)));
          } else if (value.charAt(0) == '[') {
            target.set(lastKey, Json.readArrayNode(value.getBytes(StandardCharsets.UTF_8)));
          } else {
            target.put(lastKey, value);
          }
        } else if (f.getValueType() == Heka.Field.ValueType.BOOL) {
          target.put(lastKey, f.getValueBool(0));
        } else if (f.getValueType() == ValueType.INTEGER) {
          target.put(lastKey, f.getValueInteger(0));
        } else if (f.getValueType() == Heka.Field.ValueType.DOUBLE) {
          target.put(lastKey, f.getValueDouble(0));
        }
        // FIXME: do we need to handle byte fields?
        // see code in moztelemetry and
        // https://bugzilla.mozilla.org/show_bug.cgi?id=1339421
      }
    }

    Map<String, String> attributes = new HashMap<>();
    attributes.put(Attribute.DOCUMENT_NAMESPACE, "telemetry");
    attributes.put(Attribute.HOST, message.getHostname());
    Optional.ofNullable(payload.remove("meta")).ifPresent(meta -> {
      Optional.ofNullable(meta.path("Date").textValue())
          .ifPresent(s -> attributes.put(Attribute.DATE, s));
      Optional.ofNullable(meta.path("docType").textValue())
          .ifPresent(s -> attributes.put(Attribute.DOCUMENT_TYPE, s));
      Optional.ofNullable(meta.path("appBuildId").textValue())
          .ifPresent(s -> attributes.put(Attribute.APP_BUILD_ID, s));
      Optional.ofNullable(meta.path("appName").textValue())
          .ifPresent(s -> attributes.put(Attribute.APP_NAME, s));
      Optional.ofNullable(meta.path("appUpdateChannel").textValue())
          .ifPresent(s -> attributes.put(Attribute.APP_UPDATE_CHANNEL, s));
      Optional.ofNullable(meta.path("appVersion").textValue())
          .ifPresent(s -> attributes.put(Attribute.APP_VERSION, s));
      Optional.ofNullable(meta.path("documentId").textValue())
          .ifPresent(s -> attributes.put(Attribute.DOCUMENT_ID, s));
      Optional.ofNullable(meta.path("sourceVersion").textValue())
          .ifPresent(s -> attributes.put(Attribute.DOCUMENT_VERSION, s));
      Optional.ofNullable(meta.path("geoCity").textValue())
          .ifPresent(s -> attributes.put(Attribute.GEO_CITY, s));
      Optional.ofNullable(meta.path("geoCountry").textValue())
          .ifPresent(s -> attributes.put(Attribute.GEO_COUNTRY, s));
      Optional.ofNullable(meta.path("geoSubdivision1").textValue())
          .ifPresent(s -> attributes.put(Attribute.GEO_SUBDIVISION1, s));
      Optional.ofNullable(meta.path("geoSubdivision2").textValue())
          .ifPresent(s -> attributes.put(Attribute.GEO_SUBDIVISION2, s));
      // TODO: Do heka messages contain parsed user agent info? DNT? Method? Protocol?
    });

    return new PubsubMessage(Json.asBytes(payload), attributes);
  }

  public static List<PubsubMessage> readHekaStream(InputStream is) throws IOException {
    List<PubsubMessage> decodedMessages = new ArrayList<>();

    while (true) {
      PubsubMessage o = readHekaMessage(is);
      if (o == null) {
        break;
      }
      decodedMessages.add(o);
    }
    return decodedMessages;
  }
}
