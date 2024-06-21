package com.provectus.kafka.ui.util.jsonschema;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.Lists;
import com.provectus.kafka.ui.exception.JsonAvroConversionException;
import io.confluent.kafka.serializers.AvroData;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;

// json <-> avro
public class JsonAvroConversion {

  private static final JsonMapper MAPPER = new JsonMapper();
  private static final Schema NULL_SCHEMA = Schema.create(Schema.Type.NULL);
  private static final String FORMAT = "format";
  private static final String DATE_TIME = "date-time";

  // converts json into Object that is expected input for KafkaAvroSerializer
  // (with AVRO_USE_LOGICAL_TYPE_CONVERTERS flat enabled!)
  public static Object convertJsonToAvro(String jsonString, Schema avroSchema) {
    JsonNode rootNode = null;
    try {
      rootNode = MAPPER.readTree(jsonString);
    } catch (JsonProcessingException e) {
      throw new JsonAvroConversionException("String is not a valid json");
    }
    return convert(rootNode, avroSchema);
  }

  private static Object convert(JsonNode node, Schema avroSchema) {
    switch (avroSchema.getType()) {
      case RECORD: return convertRecord(node, avroSchema);
      case MAP: return convertMap(node, avroSchema);
      case ARRAY: return convertArray(node, avroSchema);
      case ENUM: return convertEnum(node, avroSchema);
      case UNION: return convertUnion(node, avroSchema);
      case STRING: return convertString(node, avroSchema);
      case LONG: return convertLong(node, avroSchema);
      case INT: return convertInt(node, avroSchema);
      case FLOAT: return convertFloat(node);
      case DOUBLE: return convertDouble(node);
      case BOOLEAN: return convertBoolean(node);
      case NULL: return convertNull(node);
      case BYTES: return convertBytes(node, avroSchema);
      case FIXED: return convertFixed(node, avroSchema);
      default: throw new IllegalStateException("Unexpected value: " + avroSchema.getType());
    }
  }

  private static Object convertRecord(JsonNode node, Schema avroSchema) {
    assertJsonType(node, JsonNodeType.OBJECT);
    var rec = new GenericData.Record(avroSchema);
    for (Schema.Field field : avroSchema.getFields()) {
      if (node.has(field.name()) && !node.get(field.name()).isNull()) {
        rec.put(field.name(), convert(node.get(field.name()), field.schema()));
      }
    }
    return rec;
  }

  private static Object convertMap(JsonNode node, Schema avroSchema) {
    assertJsonType(node, JsonNodeType.OBJECT);
    var map = new LinkedHashMap<String, Object>();
    var valueSchema = avroSchema.getValueType();
    node.fields().forEachRemaining(f -> map.put(f.getKey(), convert(f.getValue(), valueSchema)));
    return map;
  }

  private static Object convertArray(JsonNode node, Schema avroSchema) {
    assertJsonType(node, JsonNodeType.ARRAY);
    var lst = new ArrayList<>();
    node.elements().forEachRemaining(e -> lst.add(convert(e, avroSchema.getElementType())));
    return lst;
  }

  private static Object convertEnum(JsonNode node, Schema avroSchema) {
    assertJsonType(node, JsonNodeType.STRING);
    String symbol = node.textValue();
    if (!avroSchema.getEnumSymbols().contains(symbol)) {
      throw new JsonAvroConversionException("%s is not a part of enum symbols [%s]"
          .formatted(symbol, avroSchema.getEnumSymbols()));
    }
    return new GenericData.EnumSymbol(avroSchema, symbol);
  }

  private static Object convertUnion(JsonNode node, Schema avroSchema) {
    // for types from enum (other than null) payload should be an object with single key == name of type
    // ex: schema = [ "null", "int", "string" ], possible payloads = null, { "string": "str" },  { "int": 123 }
    if (node.isNull() && avroSchema.getTypes().contains(NULL_SCHEMA)) {
      return null;
    }

    assertJsonType(node, JsonNodeType.OBJECT);
    var elements = Lists.newArrayList(node.fields());
    if (elements.size() != 1) {
      throw new JsonAvroConversionException(
          "UNION field value should be an object with single field == type name");
    }
    Map.Entry<String, JsonNode> typeNameToValue = elements.get(0);
    return findUnionType(node, avroSchema, typeNameToValue);
  }

  private static Object findUnionType(JsonNode node, Schema avroSchema, Map.Entry<String, JsonNode> typeNameToValue) {
    List<Schema> candidates = new ArrayList<>();
    for (Schema unionType : avroSchema.getTypes()) {
      if (typeNameToValue.getKey().equals(unionType.getFullName())) {
        return convert(typeNameToValue.getValue(), unionType);
      }
      if (typeNameToValue.getKey().equals(unionType.getName())) {
        candidates.add(unionType);
      }
    }
    return processUnionCandidates(node, avroSchema, typeNameToValue, candidates);
  }

  private static Object processUnionCandidates(JsonNode node, Schema avroSchema, Map.Entry<String, JsonNode> typeNameToValue, List<Schema> candidates) {
    if (candidates.size() == 1) {
      return convert(typeNameToValue.getValue(), candidates.get(0));
    }
    if (candidates.size() > 1) {
      throw new JsonAvroConversionException(
          "Can't select type within union for value '%s'. Provide full type name.".formatted(node)
      );
    }
    throw new JsonAvroConversionException(
        "json value '%s' is cannot be converted to any of union types [%s]"
            .formatted(node, avroSchema.getTypes()));
  }

  private static Object convertString(JsonNode node, Schema avroSchema) {
    if (isLogicalType(avroSchema)) {
      return processLogicalType(node, avroSchema);
    }
    assertJsonType(node, JsonNodeType.STRING);
    return node.textValue();
  }

  private static Object convertLong(JsonNode node, Schema avroSchema) {
    if (isLogicalType(avroSchema)) {
      return processLogicalType(node, avroSchema);
    }
    assertJsonType(node, JsonNodeType.NUMBER);
    assertJsonNumberType(node, JsonParser.NumberType.LONG, JsonParser.NumberType.INT);
    return node.longValue();
  }

  private static Object convertInt(JsonNode node, Schema avroSchema) {
    if (isLogicalType(avroSchema)) {
      return processLogicalType(node, avroSchema);
    }
    assertJsonType(node, JsonNodeType.NUMBER);
    assertJsonNumberType(node, JsonParser.NumberType.INT);
    return node.intValue();
  }

  private static Object convertFloat(JsonNode node) {
    assertJsonType(node, JsonNodeType.NUMBER);
    assertJsonNumberType(node, JsonParser.NumberType.DOUBLE, JsonParser.NumberType.FLOAT);
    return node.floatValue();
  }

  private static Object convertDouble(JsonNode node) {
    assertJsonType(node, JsonNodeType.NUMBER);
    assertJsonNumberType(node, JsonParser.NumberType.DOUBLE, JsonParser.NumberType.FLOAT);
    return node.doubleValue();
  }

  private static Object convertBoolean(JsonNode node) {
    assertJsonType(node, JsonNodeType.BOOLEAN);
    return node.booleanValue();
  }

  private static Object convertNull(JsonNode node) {
    assertJsonType(node, JsonNodeType.NULL);
    return null;
  }

  private static Object convertBytes(JsonNode node, Schema avroSchema) {
    if (isLogicalType(avroSchema)) {
      return processLogicalType(node, avroSchema);
    }
    assertJsonType(node, JsonNodeType.STRING);
    // logic copied from JsonDecoder::readBytes
    return ByteBuffer.wrap(node.textValue().getBytes(StandardCharsets.ISO_8859_1));
  }

  private static Object convertFixed(JsonNode node, Schema avroSchema) {
    if (isLogicalType(avroSchema)) {
      return processLogicalType(node, avroSchema);
    }
    assertJsonType(node, JsonNodeType.STRING);
    byte[] bytes = node.textValue().getBytes(StandardCharsets.ISO_8859_1);
    if (bytes.length != avroSchema.getFixedSize()) {
      throw new JsonAvroConversionException(
          "Fixed field has unexpected size %d (should be %d)"
              .formatted(bytes.length, avroSchema.getFixedSize()));
    }
    return new GenericData.Fixed(avroSchema, bytes);
  }
  //Refactoring end

  // converts output of KafkaAvroDeserializer (with AVRO_USE_LOGICAL_TYPE_CONVERTERS flat enabled!) into json.
  // Note: conversion should be compatible with AvroJsonSchemaConverter logic!
  public static JsonNode convertAvroToJson(Object obj, Schema avroSchema) {
    if (obj == null) {
      return NullNode.getInstance();
    }
    return switch (avroSchema.getType()) {
      case RECORD -> {
        var rec = (GenericData.Record) obj;
        ObjectNode node = MAPPER.createObjectNode();
        for (Schema.Field field : avroSchema.getFields()) {
          var fieldVal = rec.get(field.name());
          if (fieldVal != null) {
            node.set(field.name(), convertAvroToJson(fieldVal, field.schema()));
          }
        }
        yield node;
      }
      case MAP -> {
        ObjectNode node = MAPPER.createObjectNode();
        ((Map) obj).forEach((k, v) -> node.set(k.toString(), convertAvroToJson(v, avroSchema.getValueType())));
        yield node;
      }
      case ARRAY -> {
        var list = (List<Object>) obj;
        ArrayNode node = MAPPER.createArrayNode();
        list.forEach(e -> node.add(convertAvroToJson(e, avroSchema.getElementType())));
        yield node;
      }
      case ENUM -> {
        yield new TextNode(obj.toString());
      }
      case UNION -> {
        ObjectNode node = MAPPER.createObjectNode();
        int unionIdx = AvroData.getGenericData().resolveUnion(avroSchema, obj);
        Schema selectedType = avroSchema.getTypes().get(unionIdx);
        node.set(
            selectUnionTypeFieldName(avroSchema, selectedType, unionIdx),
            convertAvroToJson(obj, selectedType)
        );
        yield node;
      }
      case STRING -> {
        if (isLogicalType(avroSchema)) {
          yield processLogicalType(obj, avroSchema);
        }
        yield new TextNode(obj.toString());
      }
      case LONG -> {
        if (isLogicalType(avroSchema)) {
          yield processLogicalType(obj, avroSchema);
        }
        yield new LongNode((Long) obj);
      }
      case INT -> {
        if (isLogicalType(avroSchema)) {
          yield processLogicalType(obj, avroSchema);
        }
        yield new IntNode((Integer) obj);
      }
      case FLOAT -> new FloatNode((Float) obj);
      case DOUBLE -> new DoubleNode((Double) obj);
      case BOOLEAN -> BooleanNode.valueOf((Boolean) obj);
      case NULL -> NullNode.getInstance();
      case BYTES -> {
        if (isLogicalType(avroSchema)) {
          yield processLogicalType(obj, avroSchema);
        }
        ByteBuffer bytes = (ByteBuffer) obj;
        //see JsonEncoder::writeByteArray
        yield new TextNode(new String(bytes.array(), StandardCharsets.ISO_8859_1));
      }
      case FIXED -> {
        if (isLogicalType(avroSchema)) {
          yield processLogicalType(obj, avroSchema);
        }
        var fixed = (GenericData.Fixed) obj;
        yield new TextNode(new String(fixed.bytes(), StandardCharsets.ISO_8859_1));
      }
    };
  }

  // select name for a key field that represents type name of union.
  // For records selects short name, if it is possible.
  private static String selectUnionTypeFieldName(Schema unionSchema,
                                                 Schema chosenType,
                                                 int chosenTypeIdx) {
    var types = unionSchema.getTypes();
    if (types.size() == 2 && types.contains(NULL_SCHEMA)) {
      return chosenType.getName();
    }
    for (int i = 0; i < types.size(); i++) {
      if (i != chosenTypeIdx && chosenType.getName().equals(types.get(i).getName())) {
        // there is another type inside union with the same name
        // so, we have to use fullname
        return chosenType.getFullName();
      }
    }
    return chosenType.getName();
  }

  private static Object processLogicalType(JsonNode node, Schema schema) {
    return findConversion(schema)
        .map(c -> c.jsonToAvroConversion.apply(node, schema))
        .orElseThrow(() ->
            new JsonAvroConversionException("'%s' logical type is not supported"
                .formatted(schema.getLogicalType().getName())));
  }

  private static JsonNode processLogicalType(Object obj, Schema schema) {
    return findConversion(schema)
        .map(c -> c.avroToJsonConversion.apply(obj, schema))
        .orElseThrow(() ->
            new JsonAvroConversionException("'%s' logical type is not supported"
                .formatted(schema.getLogicalType().getName())));
  }

  private static Optional<LogicalTypeConversion> findConversion(Schema schema) {
    String logicalTypeName = schema.getLogicalType().getName();
    return Stream.of(LogicalTypeConversion.values())
        .filter(t -> t.name.equalsIgnoreCase(logicalTypeName))
        .findFirst();
  }

  private static boolean isLogicalType(Schema schema) {
    return schema.getLogicalType() != null;
  }

  private static void assertJsonType(JsonNode node, JsonNodeType... allowedTypes) {
    if (Stream.of(allowedTypes).noneMatch(t -> node.getNodeType() == t)) {
      throw new JsonAvroConversionException(
          "%s node has unexpected type, allowed types %s, actual type %s"
              .formatted(node, Arrays.toString(allowedTypes), node.getNodeType()));
    }
  }

  private static void assertJsonNumberType(JsonNode node, JsonParser.NumberType... allowedTypes) {
    if (Stream.of(allowedTypes).noneMatch(t -> node.numberType() == t)) {
      throw new JsonAvroConversionException(
          "%s node has unexpected numeric type, allowed types %s, actual type %s"
              .formatted(node, Arrays.toString(allowedTypes), node.numberType()));
    }
  }

  enum LogicalTypeConversion {

    UUID("uuid",
        (node, schema) -> {
          assertJsonType(node, JsonNodeType.STRING);
          return java.util.UUID.fromString(node.asText());
        },
        (obj, schema) -> {
          return new TextNode(obj.toString());
        },
        new SimpleFieldSchema(
            new SimpleJsonType(
                JsonType.Type.STRING,
                Map.of(FORMAT, new TextNode("uuid"))))
    ),

    DECIMAL("decimal",
        (node, schema) -> {
          if (node.isTextual()) {
            return new BigDecimal(node.asText());
          } else if (node.isNumber()) {
            return new BigDecimal(node.numberValue().toString());
          }
          throw new JsonAvroConversionException(
              "node '%s' can't be converted to decimal logical type"
                  .formatted(node));
        },
        (obj, schema) -> {
          return new DecimalNode((BigDecimal) obj);
        },
        new SimpleFieldSchema(new SimpleJsonType(JsonType.Type.NUMBER))
    ),

    DATE("date",
        (node, schema) -> {
          if (node.isInt()) {
            return LocalDate.ofEpochDay(node.intValue());
          } else if (node.isTextual()) {
            return LocalDate.parse(node.asText());
          } else {
            throw new JsonAvroConversionException(
                "node '%s' can't be converted to date logical type"
                    .formatted(node));
          }
        },
        (obj, schema) -> {
          return new TextNode(obj.toString());
        },
        new SimpleFieldSchema(
            new SimpleJsonType(
                JsonType.Type.STRING,
                Map.of(FORMAT, new TextNode("date"))))
    ),

    TIME_MILLIS("time-millis",
        (node, schema) -> {
          if (node.isIntegralNumber()) {
            return LocalTime.ofNanoOfDay(TimeUnit.MILLISECONDS.toNanos(node.longValue()));
          } else if (node.isTextual()) {
            return LocalTime.parse(node.asText());
          } else {
            throw new JsonAvroConversionException(
                "node '%s' can't be converted to time-millis logical type"
                    .formatted(node));
          }
        },
        (obj, schema) -> {
          return new TextNode(obj.toString());
        },
        new SimpleFieldSchema(
            new SimpleJsonType(
                JsonType.Type.STRING,
                Map.of(FORMAT, new TextNode("time"))))
    ),

    TIME_MICROS("time-micros",
        (node, schema) -> {
          if (node.isIntegralNumber()) {
            return LocalTime.ofNanoOfDay(TimeUnit.MICROSECONDS.toNanos(node.longValue()));
          } else if (node.isTextual()) {
            return LocalTime.parse(node.asText());
          } else {
            throw new JsonAvroConversionException(
                "node '%s' can't be converted to time-micros logical type"
                    .formatted(node));
          }
        },
        (obj, schema) -> {
          return new TextNode(obj.toString());
        },
        new SimpleFieldSchema(
            new SimpleJsonType(
                JsonType.Type.STRING,
                Map.of(FORMAT, new TextNode("time"))))
    ),

    TIMESTAMP_MILLIS("timestamp-millis",
        (node, schema) -> {
          if (node.isIntegralNumber()) {
            return Instant.ofEpochMilli(node.longValue());
          } else if (node.isTextual()) {
            return Instant.parse(node.asText());
          } else {
            throw new JsonAvroConversionException(
                "node '%s' can't be converted to timestamp-millis logical type"
                    .formatted(node));
          }
        },
        (obj, schema) -> {
          return new TextNode(obj.toString());
        },
        new SimpleFieldSchema(
            new SimpleJsonType(
                JsonType.Type.STRING,
                Map.of(FORMAT, new TextNode(DATE_TIME))))
    ),

    TIMESTAMP_MICROS("timestamp-micros",
        (node, schema) -> {
          if (node.isIntegralNumber()) {
            // TimeConversions.TimestampMicrosConversion for impl
            long microsFromEpoch = node.longValue();
            long epochSeconds = microsFromEpoch / (1_000_000L);
            long nanoAdjustment = (microsFromEpoch % (1_000_000L)) * 1_000L;
            return Instant.ofEpochSecond(epochSeconds, nanoAdjustment);
          } else if (node.isTextual()) {
            return Instant.parse(node.asText());
          } else {
            throw new JsonAvroConversionException(
                "node '%s' can't be converted to timestamp-millis logical type"
                    .formatted(node));
          }
        },
        (obj, schema) -> {
          return new TextNode(obj.toString());
        },
        new SimpleFieldSchema(
            new SimpleJsonType(
                JsonType.Type.STRING,
                Map.of(FORMAT, new TextNode(DATE_TIME))))
    ),

    LOCAL_TIMESTAMP_MILLIS("local-timestamp-millis",
        (node, schema) -> {
          if (node.isTextual()) {
            return LocalDateTime.parse(node.asText());
          }
          // TimeConversions.TimestampMicrosConversion for impl
          Instant instant = (Instant) TIMESTAMP_MILLIS.jsonToAvroConversion.apply(node, schema);
          return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        },
        (obj, schema) -> {
          return new TextNode(obj.toString());
        },
        new SimpleFieldSchema(
            new SimpleJsonType(
                JsonType.Type.STRING,
                Map.of(FORMAT, new TextNode(DATE_TIME))))
    ),

    LOCAL_TIMESTAMP_MICROS("local-timestamp-micros",
        (node, schema) -> {
          if (node.isTextual()) {
            return LocalDateTime.parse(node.asText());
          }
          Instant instant = (Instant) TIMESTAMP_MICROS.jsonToAvroConversion.apply(node, schema);
          return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        },
        (obj, schema) -> {
          return new TextNode(obj.toString());
        },
        new SimpleFieldSchema(
            new SimpleJsonType(
                JsonType.Type.STRING,
                Map.of(FORMAT, new TextNode(DATE_TIME))))
    );

    private final String name;
    private final BiFunction<JsonNode, Schema, Object> jsonToAvroConversion;
    private final BiFunction<Object, Schema, JsonNode> avroToJsonConversion;
    private final FieldSchema jsonSchema;

    LogicalTypeConversion(String name,
                          BiFunction<JsonNode, Schema, Object> jsonToAvroConversion,
                          BiFunction<Object, Schema, JsonNode> avroToJsonConversion,
                          FieldSchema jsonSchema) {
      this.name = name;
      this.jsonToAvroConversion = jsonToAvroConversion;
      this.avroToJsonConversion = avroToJsonConversion;
      this.jsonSchema = jsonSchema;
    }

    static Optional<FieldSchema> getJsonSchema(Schema schema) {
      if (schema.getLogicalType() == null) {
        return Optional.empty();
      }
      String logicalTypeName = schema.getLogicalType().getName();
      return Stream.of(JsonAvroConversion.LogicalTypeConversion.values())
          .filter(t -> t.name.equalsIgnoreCase(logicalTypeName))
          .map(c -> c.jsonSchema)
          .findFirst();
    }
  }


}
