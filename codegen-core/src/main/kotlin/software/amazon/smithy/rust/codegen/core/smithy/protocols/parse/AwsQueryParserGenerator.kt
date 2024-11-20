/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols.parse

import software.amazon.smithy.model.knowledge.HttpBinding
import software.amazon.smithy.model.knowledge.HttpBindingIndex
import software.amazon.smithy.model.shapes.BooleanShape
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.NumberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.SimpleShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.conditionalBlock
import software.amazon.smithy.rust.codegen.core.rustlang.escape
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.rustlang.withBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.generators.setterName
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolFunctions
import software.amazon.smithy.rust.codegen.core.smithy.wrapOptional
import software.amazon.smithy.rust.codegen.core.util.dq
import java.util.logging.Logger

/**
 * The AWS query protocol's responses are identical to REST XML's, except that they are wrapped
 * in a Response/Result tag pair:
 *
 * ```
 * <SomeOperationResponse>
 *     <SomeOperationResult>
 *         <ActualData /> <!-- This part is the same as REST XML -->
 *     </SomeOperationResult>
 * </SomeOperationResponse>
 * ```
 *
 * This class wraps [XmlBindingTraitParserGenerator] and uses it to render the vast majority
 * of the response parsing, but it overrides [operationParser] to add the protocol differences.
 */
class AwsQueryParserGenerator(
    codegenContext: CodegenContext,
    xmlErrors: RuntimeType,
    private val xmlBindingTraitParserGenerator: XmlBindingTraitParserGenerator =
        XmlBindingTraitParserGenerator(
            codegenContext,
            xmlErrors,
        ) { context, inner ->
            val operationName = codegenContext.symbolProvider.toSymbol(context.shape).name
            val responseWrapperName = operationName + "Response"
            val resultWrapperName = operationName + "Result"
            rustTemplate(
                """
                if !(${XmlBindingTraitParserGenerator.XmlName(responseWrapperName).matchExpression("start_el")}) {
                    return Err(#{XmlDecodeError}::custom(format!("invalid root, expected $responseWrapperName got {:?}", start_el)))
                }
                if let Some(mut result_tag) = decoder.next_tag() {
                    let start_el = result_tag.start_el();
                    if !(${XmlBindingTraitParserGenerator.XmlName(resultWrapperName).matchExpression("start_el")}) {
                        return Err(#{XmlDecodeError}::custom(format!("invalid result, expected $resultWrapperName got {:?}", start_el)))
                    }
                """,
                "XmlDecodeError" to context.xmlDecodeErrorType,
            )
            inner("result_tag")
            rustTemplate(
                """
                } else {
                    return Err(#{XmlDecodeError}::custom("expected $resultWrapperName tag"))
                };
                """,
                "XmlDecodeError" to context.xmlDecodeErrorType,
            )
        },
) : StructuredDataParserGenerator by xmlBindingTraitParserGenerator{
    private val protocolFunctions = ProtocolFunctions(codegenContext)
    private val symbolProvider = codegenContext.symbolProvider
    private val runtimeConfig = codegenContext.runtimeConfig
    private val model = codegenContext.model
    private val logger = Logger.getLogger(javaClass.name)
    private val requestRejection = runtimeConfig.smithyRuntimeCrate("smithy-http-server")
                .toType().resolve("protocol::aws_query::rejection::RequestRejection")

    private val codegenScope =
        arrayOf(
            "Cow" to RuntimeType.Cow,
            "DateTime" to RuntimeType.dateTime(runtimeConfig),
            "HttpBody" to RuntimeType.HttpBody,
            "header_util" to RuntimeType.smithyHttp(runtimeConfig).resolve("header"),
            "Hyper" to RuntimeType.Hyper,
            "LazyStatic" to RuntimeType.LazyStatic,
            "OnceCell" to RuntimeType.OnceCell,
            "PercentEncoding" to RuntimeType.PercentEncoding,
            "Regex" to RuntimeType.Regex,
            "SmithyTypes" to RuntimeType.smithyTypes(runtimeConfig),
            "http" to RuntimeType.Http,
            "Tracing" to RuntimeType.Tracing,
        )
    override fun serverInputParser(operationShape: OperationShape): RuntimeType? {
        val inputShape = model.expectShape(operationShape.getInputShape().toShapeId())
        var input_members = inputShape.members().toList()
        //logger.warning("[AwsQueryParserGenerator] operation: ${operationShape.toShapeId()}, inputs $input_members")
        return protocolFunctions.deserializeFn(operationShape) { fnName ->
            Attribute.AllowUnusedMut.render(this)
            rustBlock(
                "pub fn $fnName(inp: Vec<(&str, &str)>, mut builder: #1T) -> Result<#1T, #2T>",
                symbolProvider.symbolForBuilder(inputShape),
                requestRejection,
            ) {
                serverRenderShapeParser(inputShape)
            }
        }
    }

    private fun RustWriter.serverRenderShapeParser(
        shape: Shape, // 
    ){

        var shapeMemberShapeIds = model.expectShape(shape.toShapeId()).members().toList()

        shapeMemberShapeIds.forEachIndexed { idx, it ->
            var memberShape = model.expectShape(it.toShapeId(), MemberShape::class.java)
            var targetShape = model.expectShape(memberShape.target)
            //logger.warning("[AwsQueryParserGenerator] member: ${memberShape.toShapeId()}, ${targetShape.toShapeId()}")
            var memberName = symbolProvider.toMemberName(memberShape)
            var memberNameSeen = "${memberName}_seen"
           
            when {
                targetShape.isMapShape() || targetShape.isStructureShape() -> {
                    var memberLocationName = "${it.getMemberName()}."  
                    rust("let (inp, values): (Vec<_>, Vec<_>) = inp.into_iter().partition(|(k,_v)| k.starts_with(${memberLocationName.dq()}));")   
                    rust("let values: Vec<_> = values.into_iter().map(|(k,v)| (k.strip_prefix(${memberLocationName.dq()}).unwrap(), v)).collect();") 
                    withBlockTemplate("builder = builder.${memberShape.setterName()}(Some(", "?));"){
                        parseStructure(model.expectShape(memberShape.target, StructureShape::class.java))
                    } 
                }

                targetShape.isSetShape() || targetShape.isListShape() -> {
                    var memberLocationName = "${it.getMemberName()}.member."
                    rust("let (inp, values): (Vec<_>, Vec<_>) = inp.into_iter().partition(|(k,_v)| k.starts_with(${memberLocationName.dq()}));")
                    rust("let values: Vec<_> = values.into_iter().map(|(k,v)| (k.strip_prefix(${memberLocationName.dq()}).unwrap(), v)).collect();")
                    withBlockTemplate("builder = builder.${memberShape.setterName()}(Some(", "?));"){
                        parseList(model.expectShape(memberShape.target, CollectionShape::class.java))
                    }                  
                }
                else -> {
                    rust("let mut ${memberNameSeen} = false;")
                    rust("let (inp, values): (Vec<_>, Vec<_>) = inp.into_iter().partition(|(k,_v)| k == ${it.getMemberName().dq()});")

                    rustTemplate(
                        """
                        for (k, v) in values {
                            if !${memberNameSeen} {
                                builder = builder.${memberShape.setterName()}(Some({
                        """.trimIndent()
                    )

                    generateParseStrFn(targetShape, false)

                    rustTemplate(
                        """
                            }));
                                ${memberNameSeen} = true;
                                break;
                            }
                        }
                        """.trimIndent()
                    )                        
                }
            }

            
        }
        rust("Ok(builder)")
    }

    // list 没有builder
    private fun RustWriter.parseList(
        target: CollectionShape,
    ){
        val member = target.member
        var targetShape = model.expectShape(member.target)
        val listParser =
            protocolFunctions.deserializeFn(target) { fnName ->
                rustBlockTemplate(
                    "pub fn $fnName(inp: Vec<(&str, &str)>) -> Result<#{List}, #{RequestRejection}>",
                    *codegenScope,
                    "List" to symbolProvider.toSymbol(target),
                    "RequestRejection" to requestRejection,
                ) {
                    rust("let mut out = std::vec::Vec::new();")
                    when {
                        targetShape.isMapShape() || targetShape.isStructureShape() -> {
                            //[Tags.member].n.Tag
                            rustBlock(
                                """
                                let mut inp = inp;
                                let mut value = Vec::new();
                                for i in 1..200
                                """
                            ){
                                rust(
                                    """
                                    (inp, values) = inp.into_iter().partition(|(k,_v)| k.starts_with(&format!("{i}.")));
                                    if value.len() <= 0 {
                                        break;
                                    }
                                    let values: Vec<_> = values.into_iter().map(|(k,v)| (k.strip_prefix(&format!("{i}.")).unwrap(), v)).collect(); 
                                    """
                                )
                                withBlock("out.push(", ");") {
                                    parseStructure(model.expectShape(member.target, StructureShape::class.java))
                                }                            

                            }
  
                        }

                        targetShape.isSetShape() || targetShape.isListShape() -> {
                            //[Tags.member].n.Tags.n
                            rustBlock(
                                """
                                let mut inp = inp;
                                let mut value = Vec::new();
                                for i in 1..200
                                """
                            ){
                                rust(
                                    """
                                    (inp, values) = inp.into_iter().partition(|(k,_v)| k.starts_with(&format!("{i}.")));
                                    if value.len() <= 0 {
                                        break;
                                    }
                                    let values: Vec<_> = values.into_iter().map(|(k,v)| (k.strip_prefix(&format!("{i}.")).unwrap(), v)).collect(); 
                                    """
                                )
                                withBlock("out.push(", ");") {
                                    parseList(model.expectShape(member.target, CollectionShape::class.java))
                                }                            

                            }
                        }
                        else -> {// collection of simple shape
                            //[Tags.member].n
                            rustBlock("for (_k, v) in inp"){
                                withBlock("out.push({", "});") {
                                    generateParseStrFn(targetShape, false)
                                }
                                
                            }                         
                        }
                    }
                    
                    rust("Ok(out)")
                }
            }
         rust("#T(values)", listParser)
    }

    private fun RustWriter.parseStructure(
        target : StructureShape
    ){
        logger.warning("[]$target")
        var structureParser = protocolFunctions.deserializeFn(target) { fnName ->
            Attribute.AllowUnusedMut.render(this)
            rustBlock(
                "pub fn $fnName(inp: Vec<(&str, &str)>, mut builder: #1T) -> Result<#1T, #2T>",
                symbolProvider.symbolForBuilder(target),
                requestRejection,
            ) {

                serverRenderShapeParser(target)
            }
        }
        rustBlock(""){
            rust(
                "let mut builder = #T::default();",
                symbolProvider.symbolForBuilder(target),
            )
            rust("builder = #T(values, builder)?;", structureParser)
            rust("builder.build()?")
        }
        
    }


    private fun RustWriter.generateParseStrFn(
        target: Shape,
        percentDecoding: Boolean,
    ) {    
                

                when {
                    target.isStringShape -> {
                        if (percentDecoding) {
                            rustTemplate(
                                """
                                let value = #{PercentEncoding}::percent_decode_str(v).decode_utf8()?.into_owned();
                                """,
                                *codegenScope,
                            )
                        } else {
                            rust("let v = value.to_owned();")
                        }
                    }
                    target.isTimestampShape -> {                        
                        val timestampFormatType = RuntimeType.smithyTypes(runtimeConfig).resolve("date_time::Format::DateTime")

                        if (percentDecoding) {
                            rustTemplate(
                                """
                                let value = #{PercentEncoding}::percent_decode_str(v).decode_utf8()?;
                                let value = #{DateTime}::from_str(value.as_ref(), #{format})?
                                """,
                                *codegenScope,
                                "format" to timestampFormatType,
                            )
                        } else {
                            rustTemplate(
                                """
                                let value = #{DateTime}::from_str(v, #{format})?
                                """,
                                *codegenScope,
                                "format" to timestampFormatType,
                            )
                        }
                        rust(";")
                    }
                    else -> {
                        check(target is NumberShape || target is BooleanShape)
                        rustTemplate(
                            """
                            let value = <_ as #{PrimitiveParse}>::parse_smithy_primitive(v)?;
                            """,
                            "PrimitiveParse" to RuntimeType.smithyTypes(runtimeConfig).resolve("primitive::Parse"),
                        )
                    }
                }
                rust("value")            
    
    }
}
