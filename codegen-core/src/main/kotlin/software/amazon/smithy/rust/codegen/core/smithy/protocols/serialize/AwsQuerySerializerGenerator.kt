/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize

import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape

import software.amazon.smithy.model.traits.XmlFlattenedTrait
import software.amazon.smithy.model.traits.XmlNameTrait
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.util.getTrait

import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBindingResolver
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpLocation

import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import java.util.logging.Logger




class AwsQuerySerializerGenerator(codegenContext: CodegenContext, var httpBindingResolver: HttpBindingResolver) : QuerySerializerGenerator(codegenContext) {
    override val protocolName: String get() = "AWS Query"
    protected val logger = Logger.getLogger(javaClass.name)

    override fun MemberShape.queryKeyName(prioritizedFallback: String?): String =
        getTrait<XmlNameTrait>()?.value ?: memberName

    override fun MemberShape.isFlattened(): Boolean = getTrait<XmlFlattenedTrait>() != null

    override fun operationOutputSerializer(operationShape: OperationShape): RuntimeType? {
        // Don't generate an operation CBOR serializer if there was no operation output shape in the
        // original (untransformed) model.
        if (!OperationNormalizer.hadUserModeledOperationOutput(operationShape, model)) {
            return null
        }

        val httpDocumentMembers = httpBindingResolver.responseMembers(operationShape, HttpLocation.DOCUMENT)
        logger.warning("[AwsQuerySerializerGenerator] httpDocumentMembers $httpDocumentMembers") 
        logger.warning("[AwsQuerySerializerGenerator] Generating Rust server for operationShape $operationShape, protocol ${codegenContext.protocol}")
        var outputShape = operationShape.getOutputShape()

        logger.warning("[rust-server-codegen] Generating Rust server for outputShape $outputShape, protocol ${codegenContext.protocol}")
        
        TODO("Not yet implemented")
    }

    override fun serverErrorSerializer(shape: ShapeId): RuntimeType {
        TODO("Not yet implemented")
    }
}
