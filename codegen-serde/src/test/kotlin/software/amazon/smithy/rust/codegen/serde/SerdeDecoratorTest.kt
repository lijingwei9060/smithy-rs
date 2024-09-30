/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.serde

import org.junit.jupiter.api.Test
import software.amazon.smithy.rust.codegen.client.testutil.clientIntegrationTest
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.CratesIo
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.testutil.IntegrationTestParams
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.integrationTest
import software.amazon.smithy.rust.codegen.core.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.runCommand
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverIntegrationTest

class SerdeDecoratorTest {
    private val params =
        IntegrationTestParams(cargoCommand = "cargo test --all-features", service = "com.example#HelloService")
    private val simpleModel =
        """
        namespace com.example
        use smithy.rust#serde
        use aws.protocols#awsJson1_0
        use smithy.framework#ValidationException
        @awsJson1_0
        service HelloService {
            operations: [SayHello, SayGoodBye, Streaming],
            version: "1"
        }
        operation SayHello {
            input: TestInput
            errors: [ValidationException]
        }

        structure Recursive {
            inner: RecursiveList
        }

        list RecursiveList {
            member: Recursive
        }

        @serde
        operation Streaming {
            input: StreamingInput
            errors: [ValidationException]
        }

        structure StreamingInput {
            @required
            data: StreamingBlob
        }

        @streaming
        blob StreamingBlob

        @serde
        structure TestInput {
           foo: SensitiveString,
           e: TestEnum,
           nested: Nested,
           union: U,
           document: Document,
           blob: SensitiveBlob,
           constrained: Constrained,
           recursive: Recursive,
           map: EnumKeyedMap,
           float: Float,
           double: Double
        }

        structure Constrained {
            shortList: ShortList
            shortMap: ShortMap
            shortBlob: ShortBlob
            rangedInt: RangedInteger,
            rangedLong: RangedLong
        }

        @range(max: 10)
        integer RangedInteger

        @range(max: 10)
        long RangedLong

        @length(max: 10)
        blob ShortBlob

        @length(max: 10)
        map ShortMap {
            key: String,
            value: Nested
        }

        @length(max: 10)
        map EnumKeyedMap {
            key: TestEnum
            value: TestEnum
        }

        @length(max: 10)
        string ShortString

        @length(max: 10)
        list ShortList {
            member: Nested
        }

        @sensitive
        blob SensitiveBlob

        @sensitive
        string SensitiveString

        @sensitive
        enum TestEnum {
            A,
            B,
            C,
            D
        }

        @sensitive
        union U {
            nested: Nested,
            enum: TestEnum,
            other: Unit
        }

        structure Nested {
          @required
          int: Integer,
          float: Float,
          double: Double,
          sensitive: Timestamps,
          notSensitive: AlsoTimestamps,
          manyEnums: TestEnumList,
          sparse: SparseList
          map: SparseMap
        }

        list TestEnumList {
            member: TestEnum
        }

        map Timestamps {
            key: String
            value: SensitiveTimestamp
        }

        map AlsoTimestamps {
            key: String
            value: Timestamp
        }

        @sparse
        map SparseMap {
            key: String
            value: Timestamps
        }

        @sensitive
        timestamp SensitiveTimestamp

        @sparse
        list SparseList {
            member: TestEnum
        }

        operation SayGoodBye {
            input: NotSerde
        }
        structure NotSerde {}
        """.asSmithyModel(smithyVersion = "2")

    @Test
    fun generateSerializersThatWorkServer() {
        serverIntegrationTest(simpleModel, params = params) { ctx, crate ->
            val codegenScope =
                arrayOf(
                    "crate" to RustType.Opaque(ctx.moduleUseName()),
                    "serde_json" to CargoDependency("serde_json", CratesIo("1")).toDevDependency().toType(),
                    // we need the derive feature
                    "serde" to CargoDependency.Serde.toDevDependency().toType(),
                )

            crate.integrationTest("test_serde") {
                unitTest("input_serialized") {
                    rustTemplate(
                        """
                        use #{crate}::model::{Nested, U, TestEnum};
                        use #{crate}::input::SayHelloInput;
                        use #{crate}::serde::*;
                        use std::collections::HashMap;
                        use std::time::UNIX_EPOCH;
                        use aws_smithy_types::{DateTime, Document, Blob};
                        let sensitive_map = HashMap::from([("a".to_string(), DateTime::from(UNIX_EPOCH))]);
                        let input = SayHelloInput::builder()
                            .foo(Some("foo-value".to_string()))
                            .e(Some(TestEnum::A))
                            .document(Some(Document::String("hello!".into())))
                            .blob(Some(Blob::new("hello")))
                            .float(Some(f32::INFINITY))
                            .double(Some(f64::NAN))
                            .nested(Some(Nested::builder()
                                .int(5)
                                .float(Some(f32::NEG_INFINITY))
                                .double(Some(f64::NEG_INFINITY))
                                .sensitive(Some(sensitive_map.clone()))
                                .not_sensitive(Some(sensitive_map))
                                .many_enums(Some(vec![TestEnum::A]))
                                .sparse(Some(vec![None, Some(TestEnum::A), Some(TestEnum::B)]))
                                .build().unwrap()
                            ))
                            .union(Some(U::Enum(TestEnum::B)))
                            .build()
                            .unwrap();
                        let mut settings = SerializationSettings::default();
                        let serialized = #{serde_json}::to_string(&input.serialize_ref(&settings)).expect("failed to serialize");
                        assert_eq!(serialized, ${expectedNoRedactions.dq()});
                        settings.redact_sensitive_fields = true;
                        let serialized = #{serde_json}::to_string(&input.serialize_ref(&settings)).expect("failed to serialize");
                        assert_eq!(serialized, ${expectedRedacted.dq()});
                        """,
                        *codegenScope,
                    )
                }

                unitTest("serde_of_bytestream") {
                    rustTemplate(
                        """
                        use #{crate}::input::StreamingInput;
                        use #{crate}::types::ByteStream;
                        use #{crate}::serde::*;
                        let input = StreamingInput::builder().data(ByteStream::from_static(b"123")).build().unwrap();
                        let settings = SerializationSettings::default();
                        let serialized = #{serde_json}::to_string(&input.serialize_ref(&settings)).expect("failed to serialize");
                        assert_eq!(serialized, ${expectedStreaming.dq()});

                        """,
                        *codegenScope,
                    )
                }

                unitTest("delegated_serde") {
                    rustTemplate(
                        """
                        use #{crate}::input::SayHelloInput;
                        use #{crate}::serde::*;
                        ##[derive(#{serde}::Serialize)]
                        struct MyRecord {
                            ##[serde(serialize_with = "serialize_redacted")]
                            redact_field: SayHelloInput,
                            ##[serde(serialize_with = "serialize_unredacted")]
                            unredacted_field: SayHelloInput
                        }
                        let input = SayHelloInput::builder().foo(Some("foo-value".to_string())).build().unwrap();

                        let field = MyRecord {
                            redact_field: input.clone(),
                            unredacted_field: input
                        };
                        let serialized = #{serde_json}::to_string(&field).expect("failed to serialize");
                        assert_eq!(serialized, r##"{"redact_field":{"foo":"<redacted>"},"unredacted_field":{"foo":"foo-value"}}"##);
                        """,
                        *codegenScope,
                    )
                }
            }
        }
    }

    private val expectedNoRedactions =
        """{
        "foo": "foo-value",
        "e": "A",
        "nested": {
          "int": 5,
          "float": "-Infinity",
          "double": "-Infinity",
          "sensitive": {
            "a": "1970-01-01T00:00:00Z"
          },
          "notSensitive": {
            "a": "1970-01-01T00:00:00Z"
          },
          "manyEnums": [
            "A"
          ],
          "sparse": [null, "A", "B"]
        },
        "union": {
          "enum": "B"
        },
        "document": "hello!",
        "blob": "aGVsbG8=",
        "float": "Infinity",
        "double": "NaN"
    }""".replace("\\s".toRegex(), "")

    private val expectedRedacted =
        """{
        "foo": "<redacted>",
        "e": "<redacted>",
        "nested": {
          "int": 5,
          "float": "-Infinity",
          "double": "-Infinity",
          "sensitive": {
            "a": "<redacted>"
          },
          "notSensitive": {
            "a": "1970-01-01T00:00:00Z"
          },
          "manyEnums": [
            "<redacted>"
          ],
          "sparse": [null, "<redacted>", "<redacted>"]
        },
        "union": "<redacted>",
        "document": "hello!",
        "blob": "<redacted>",
        "float": "Infinity",
        "double": "NaN"
        }
        """.replace("\\s".toRegex(), "")

    private val expectedStreaming = """{"data":"MTIz"}"""

    @Test
    fun generateSerializersThatWorkClient() {
        val path =
            clientIntegrationTest(simpleModel, params = params) { ctx, crate ->
                val codegenScope =
                    arrayOf(
                        "crate" to RustType.Opaque(ctx.moduleUseName()),
                        "serde_json" to CargoDependency("serde_json", CratesIo("1")).toDevDependency().toType(),
                        "serde_cbor" to CargoDependency("serde_cbor", CratesIo("0.11.2")).toDevDependency().toType(),
                        // we need the derive feature
                        "serde" to CargoDependency.Serde.toDevDependency().toType(),
                    )

                crate.integrationTest("test_serde") {
                    unitTest("input_serialized") {
                        rustTemplate(
                            """
                            use #{crate}::types::{Nested, U, TestEnum};
                            use #{crate}::serde::*;
                            use std::time::UNIX_EPOCH;
                            use aws_smithy_types::{DateTime, Document, Blob};
                            let input = #{crate}::operation::say_hello::SayHelloInput::builder()
                                .foo("foo-value")
                                .e("A".into())
                                .document(Document::String("hello!".into()))
                                .blob(Blob::new("hello"))
                                .float(f32::INFINITY)
                                .double(f64::NAN)
                                .nested(Nested::builder()
                                    .int(5)
                                    .float(f32::NEG_INFINITY)
                                    .double(f64::NEG_INFINITY)
                                    .sensitive("a", DateTime::from(UNIX_EPOCH))
                                    .not_sensitive("a", DateTime::from(UNIX_EPOCH))
                                    .many_enums("A".into())
                                    .sparse(None).sparse(Some(TestEnum::A)).sparse(Some(TestEnum::B))
                                    .build().unwrap()
                                )
                                .union(U::Enum("B".into()))
                                .build()
                                .unwrap();
                            let mut settings = #{crate}::serde::SerializationSettings::default();
                            settings.out_of_range_floats_as_strings = true;
                            let serialized = #{serde_json}::to_string(&input.serialize_ref(&settings)).expect("failed to serialize");
                            assert_eq!(serialized, ${expectedNoRedactions.dq()});
                            settings.redact_sensitive_fields = true;
                            let serialized = #{serde_json}::to_string(&input.serialize_ref(&settings)).expect("failed to serialize");
                            assert_eq!(serialized, ${expectedRedacted.dq()});
                            settings.out_of_range_floats_as_strings = false;
                            let serialized = #{serde_json}::to_string(&input.serialize_ref(&settings)).expect("failed to serialize");
                            assert_ne!(serialized, ${expectedRedacted.dq()});
                            """,
                            *codegenScope,
                        )
                    }

                    unitTest("serde_of_bytestream") {
                        rustTemplate(
                            """
                            use #{crate}::operation::streaming::StreamingInput;
                            use #{crate}::primitives::ByteStream;
                            use #{crate}::serde::*;
                            let input = StreamingInput::builder().data(ByteStream::from_static(b"123")).build().unwrap();
                            let settings = SerializationSettings::default();
                            let serialized = #{serde_json}::to_string(&input.serialize_ref(&settings)).expect("failed to serialize");
                            assert_eq!(serialized, ${expectedStreaming.dq()});
                            """,
                            *codegenScope,
                        )
                    }

                    unitTest("delegated_serde") {
                        rustTemplate(
                            """
                            use #{crate}::operation::say_hello::SayHelloInput;
                            use #{crate}::serde::*;
                            ##[derive(#{serde}::Serialize)]
                            struct MyRecord {
                                ##[serde(serialize_with = "serialize_redacted")]
                                redact_field: SayHelloInput,
                                ##[serde(serialize_with = "serialize_unredacted")]
                                unredacted_field: SayHelloInput
                            }
                            let input = SayHelloInput::builder().foo("foo-value").build().unwrap();

                            let field = MyRecord {
                                redact_field: input.clone(),
                                unredacted_field: input
                            };
                            let serialized = #{serde_json}::to_string(&field).expect("failed to serialize");
                            assert_eq!(serialized, r##"{"redact_field":{"foo":"<redacted>"},"unredacted_field":{"foo":"foo-value"}}"##);
                            """,
                            *codegenScope,
                        )
                    }

                    unitTest("cbor") {
                        rustTemplate(
                            """
                            use #{crate}::operation::streaming::StreamingInput;
                            use #{crate}::primitives::ByteStream;
                            use #{crate}::serde::*;
                            let input = StreamingInput::builder().data(ByteStream::from_static(b"123")).build().unwrap();
                            let settings = SerializationSettings::default();
                            let serialized = #{serde_cbor}::to_vec(&input.serialize_ref(&settings)).expect("failed to serialize");
                            assert_eq!(serialized, b"\xa1ddataC123");
                            """,
                            *codegenScope,
                        )
                    }
                }
            }
        "cargo clippy --all-features".runCommand(path)
    }
}