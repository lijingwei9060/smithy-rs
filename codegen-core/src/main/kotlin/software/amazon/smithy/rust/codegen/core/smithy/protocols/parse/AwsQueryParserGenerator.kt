/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols.parse

import software.amazon.smithy.model.knowledge.HttpBinding
import software.amazon.smithy.model.knowledge.HttpBindingIndex
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.BooleanShape
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.DocumentShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.NumberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.SparseTrait
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
import software.amazon.smithy.rust.codegen.core.smithy.isOptional
import software.amazon.smithy.rust.codegen.core.smithy.isRustBoxed
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBindingResolver
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolFunctions
import software.amazon.smithy.rust.codegen.core.smithy.wrapOptional
import software.amazon.smithy.rust.codegen.core.util.PANIC
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.isTargetUnit
import software.amazon.smithy.rust.codegen.core.util.outputShape
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
    private val httpBindingResolver: HttpBindingResolver,
    /**
     * Whether we should parse a value for a shape into its associated unconstrained type. For example, when the shape
     * is a `StructureShape`, we should construct and return a builder instead of building into the final `struct` the
     * user gets. This is only relevant for the server, that parses the incoming request and only after enforces
     * constraint traits.
     *
     * The function returns a data class that signals the return symbol that should be parsed, and whether it's
     * unconstrained or not.
     */
    private val returnSymbolToParse: (Shape) -> ReturnSymbolToParse = { shape ->
        ReturnSymbolToParse(codegenContext.symbolProvider.toSymbol(shape), false)
    },
) : StructuredDataParserGenerator {
    private val model = codegenContext.model    
    private val symbolProvider = codegenContext.symbolProvider
    private val runtimeConfig = codegenContext.runtimeConfig
    private val codegenTarget = codegenContext.target

    private val protocolFunctions = ProtocolFunctions(codegenContext)
    private val builderInstantiator = codegenContext.builderInstantiator()

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
            "Error" to requestRejection,
            "HashMap" to RuntimeType.HashMap,
        )

    override fun payloadParser(member: MemberShape): RuntimeType {
        val shape = model.expectShape(member.target)
        val returnSymbolToParse = returnSymbolToParse(shape)
        check(shape is UnionShape || shape is StructureShape || shape is DocumentShape) {
            "Payload parser should only be used on structure shapes, union shapes, and document shapes."
        }
        return protocolFunctions.deserializeFn(shape, fnNameSuffix = "payload") { fnName ->
            rustBlockTemplate(
                "pub(crate) fn $fnName(input: &[u8]) -> Result<#{ReturnType}, PayloadDontKnow>",
                *codegenScope,
                "ReturnType" to returnSymbolToParse.symbol,
            ) {
                val input =
                    if (shape is DocumentShape) {
                        "input"
                    } else {
                        "(input)"
                    }

                rustTemplate(
                    """
                    let mut tokens_owned = ($input).peekable();
                    let tokens = &mut tokens_owned;
                    """,
                    *codegenScope,
                )
                rust("let result =")
                // deserializeMember(member)
                rustTemplate(".ok_or_else(|| {Error}::custom(\"expected payload member value\"));", *codegenScope)
                
                rust("result")
            }
        }
    }

    override fun operationParser(operationShape: OperationShape): RuntimeType? {
        return null
    }

    override fun errorParser(errorShape: StructureShape): RuntimeType? {
        return null
    }
    override fun serverInputParser(operationShape: OperationShape): RuntimeType? {
        val inputShape = operationShape.inputShape(model)
        var includedMembers = inputShape.members().toList()
        return structureParser(operationShape, symbolProvider.symbolForBuilder(inputShape), includedMembers)
    }

    /**
     * Reusable structure parser implementation that can be used to generate parsing code for
     * operation, error and structure shapes.
     * We still generate the parser symbol even if there are no included members because the server
     * generation requires parsers for all input structures.
     */
    private fun structureParser(
        shape: Shape,
        builderSymbol: Symbol,
        includedMembers: List<MemberShape>,
        fnNameSuffix: String? = null,
    ): RuntimeType {
        return protocolFunctions.deserializeFn(shape, fnNameSuffix) { fnName ->
        val unusedMut = if (includedMembers.isEmpty()) "##[allow(unused_mut)] " else ""
        rustBlockTemplate(
                "pub(crate) fn $fnName(##[allow(unused_variables)]inp: #{HashMap}<#{Cow}<str>, #{Cow}<str>>, ${unusedMut}mut builder: #{Builder}) -> Result<#{Builder}, #{Error}>",
                "Builder" to builderSymbol,
                *codegenScope,
            ) {
                // 
                if (includedMembers.isNotEmpty()) {
                  rustTemplate(
                        """
                        ##[allow(unused_assignments)]
                        let mut inp = inp;
                        ##[allow(unused_assignments)]
                        let mut this_property = #{HashMap}::new();
                        """,
                        *codegenScope,
                    )  
                }
                
                deserializeStructInner(includedMembers)
                rust("Ok(builder)")
            }
        }
    }

    private fun RustWriter.deserializeStructInner(members: Collection<MemberShape>) {        
        for (member in members) {            
            when (codegenTarget) {
                CodegenTarget.CLIENT ->{

                }
                CodegenTarget.SERVER -> {
                    rustBlock(""){
                        rust("// extract values for this property")
                        if (isCollectionMember(member)) { // collection list
                            var key = "${member.getMemberName()}.member."
                            rustTemplate(
                                """
                                (##[allow(unused_assignments)]inp, this_property) = inp.into_iter().partition(|(k,_v)| k.starts_with("${key}")); 
                                this_property = this_property.into_iter().map(|(k,v)|  (#{Cow}::Owned(k.strip_prefix("${key}").unwrap().to_owned()), v)).collect();
                                """,
                                *codegenScope
                            )
                        }else if (isStructureMember(member)){ // structure or map
                            var key = "${member.getMemberName()}."
                            rustTemplate(
                                """
                                (##[allow(unused_assignments)]inp, this_property) = inp.into_iter().partition(|(k,_v)| k.starts_with("${key}")); 
                                this_property = this_property.into_iter().map(|(k,v)|  (#{Cow}::Owned(k.strip_prefix("${key}").unwrap().to_owned()), v)).collect();
                                """,
                                *codegenScope
                            )
                        }else{ // simple shape
                            rust(
                                """
                                (##[allow(unused_assignments)]inp, this_property) = inp.into_iter().partition(|(k,_v)| *k == ${member.getMemberName().dq()}); 
                                """
                            )
                        }
                        
                        if (symbolProvider.toSymbol(member).isOptional()) {
                            withBlock("builder = builder.${member.setterName()}(", ");") {
                                deserializeMember(member)
                            }
                        } else {
                            rust("if let Some(v) = ")
                            deserializeMember(member)
                            rust(
                                """
                                {
                                    builder = builder.${member.setterName()}(v);
                                }
                                """,
                            )
                        }
                    }                    
                }
            }   
        }       
    }

    private fun RustWriter.deserializeStruct(shape: StructureShape) {
        val returnSymbolToParse = returnSymbolToParse(shape)
        val nestedParser =
            protocolFunctions.deserializeFn(shape) { fnName ->
                rustBlockTemplate(
                    """
                    pub(crate) fn $fnName(##[allow(unused_variables)] inp: #{HashMap}<#{Cow}<str>, #{Cow}<str>>) -> Result<Option<#{ReturnType}>, #{Error}>
                    """,
                    "ReturnType" to returnSymbolToParse.symbol,
                    *codegenScope,
                ) {
                    if (shape.members().isNotEmpty()) {
                        // not empty
                        rustTemplate(
                            """
                            ##[allow(unused_assignments)]
                            let mut inp = inp;
                            ##[allow(unused_assignments)]
                            let mut this_property = #{HashMap}::new();
                            """,
                            *codegenScope,
                        )
                    }
                   
                
                    Attribute.AllowUnusedMut.render(this)
                    rustTemplate(
                        "let mut builder = #{Builder}::default();",
                        *codegenScope,
                        "Builder" to symbolProvider.symbolForBuilder(shape),
                    )
                    deserializeStructInner(shape.members())
                    val builder =
                        builderInstantiator.finalizeBuilder(
                            "builder", shape,
                        ) {
                            rustTemplate(
                                """|err|#{Error}::custom_source("Response was invalid", err)""", *codegenScope,
                            )
                        }
                    rust("Ok(Some(#T))", builder)
                }
            }
        rust("#T(this_property)?", nestedParser)
    }

    private fun RustWriter.deserializeCollection(shape: CollectionShape) {
        val isSparse = shape.hasTrait<SparseTrait>()
        val (returnSymbol, returnUnconstrainedType) = returnSymbolToParse(shape)
        val parser =
            protocolFunctions.deserializeFn(shape) { fnName ->
                rustBlockTemplate(
                    """
                    pub(crate) fn $fnName(inp: #{HashMap}<#{Cow}<str>, #{Cow}<str>>) -> Result<Option<#{ReturnType}>, #{Error}>
                    """,
                    "ReturnType" to returnSymbol,
                    *codegenScope,
                ) {
                    
                    rust("let mut items = Vec::new();")
                    rustBlockTemplate(
                        """
                        ##[allow(unused_assignments)]
                        let mut inp = inp;
                        ##[allow(unused_assignments)]
                        let mut this_property = #{HashMap}::new();
                        for i in 1..200
                        """,
                        *codegenScope,
                    ) {
                        
                        rustTemplate(
                            """
                            (##[allow(unused_assignments)]inp, this_property) = inp.into_iter().partition(|(k,_v)| k.starts_with(&format!("{i}"))); 
                            this_property = this_property.into_iter().map(|(k,v)|  (#{Cow}::Owned(k.strip_prefix(&format!("{i}")).unwrap().to_owned()), v)).collect();
                            """,
                            *codegenScope
                        )
                            
                        if (isSparse) {
                            withBlock("items.push(", ");") {
                                deserializeMember(shape.member)
                            }
                        } else {
                            withBlock("let value =", ";") {
                                deserializeMember(shape.member)
                            }
                            rustTemplate(
                                """
                                if let Some(value) = value {
                                    items.push(value);
                                } else {
                                    return Err(#{Error}::ListContainNull("${shape.getMember().getMemberName()}".to_string()));
                                }
                                """,
                                *codegenScope,
                            )
                            
                        }
                            
                        
                    }
                    if (returnUnconstrainedType) {
                        rust("Ok(Some(#{T}(items)))", returnSymbol)
                    } else {
                        rust("Ok(Some(items))")
                    }
                    
                }
            }
        rust("#T(this_property)?", parser)
    }

    private fun isCollectionMember(memberShape: MemberShape): Boolean {
        when (model.expectShape(memberShape.target)) {
            is StringShape -> return false
            is BooleanShape ->  return false
            is NumberShape ->  return false
            is BlobShape ->  return false
            is TimestampShape ->  return false
            is CollectionShape ->  return true
            is MapShape ->  return false
            is StructureShape ->  return false
            is UnionShape ->  return false
            is DocumentShape ->  return false
            else ->  return false
        }        
    }

    private fun isStructureMember(memberShape: MemberShape): Boolean {
        when (model.expectShape(memberShape.target)) {
            is StringShape -> return false
            is BooleanShape ->  return false
            is NumberShape ->  return false
            is BlobShape ->  return false
            is TimestampShape ->  return false
            is CollectionShape ->  return false
            is MapShape ->  return true
            is StructureShape ->  return true
            is UnionShape ->  return false
            is DocumentShape ->  return false
            else ->  return false
        }        
    }

    private fun RustWriter.deserializeMember(memberShape: MemberShape) {
        var key = memberShape.getMemberName()
        when (val target = model.expectShape(memberShape.target)) {
            is StringShape -> {
                withBlock("this_property.get(${key.dq()}).map(|u|", ")"){
                    deserializeString(target)
                }                
            } 
            is BooleanShape -> {
                withBlock("this_property.get(${key.dq()}).map(|u|", ").transpose()?"){
                    rustTemplate(
                        """
                        <bool as #{PrimitiveParse}>::parse_smithy_primitive(u).map_err(|_e|#{Error}::ParseFailed("${key}".to_string()))
                        """,
                        "PrimitiveParse" to RuntimeType.smithyTypes(runtimeConfig).resolve("primitive::Parse"),
                        *codegenScope
                    )
                }    
            }
            is NumberShape -> {
                withBlock("this_property.get(${key.dq()}).map(|u|", ").transpose()?"){
                    rustTemplate(
                        """
                        <i32 as #{PrimitiveParse}>::parse_smithy_primitive(u).map_err(|_e|#{Error}::ParseFailed("${key}".to_string()))
                        """,
                        "PrimitiveParse" to RuntimeType.smithyTypes(runtimeConfig).resolve("primitive::Parse"),
                        *codegenScope
                    )
                }
            }
            is BlobShape -> {}
            is TimestampShape -> {
                withBlock("this_property.get(${key.dq()}).map(|u|", ").transpose()?"){
                    val timestampFormatType = RuntimeType.smithyTypes(runtimeConfig).resolve("date_time::Format::DateTime")
                    rustTemplate(
                        """
                        let value = #{DateTime}::from_str(u, #{format})
                        """,
                        *codegenScope,
                        "format" to timestampFormatType,
                    )
                }
            }
            is CollectionShape -> deserializeCollection(target)
            is MapShape -> PANIC("unexpected map shape: $target")
            is StructureShape -> deserializeStruct(target)
            is UnionShape -> PANIC("unexpected union shape: $target")
            is DocumentShape -> PANIC("unexpected document shape: $target")
            else -> PANIC("unexpected shape: $target")
        }
        val symbol = symbolProvider.toSymbol(memberShape)
        if (symbol.isRustBoxed()) {
            rust(".map(Box::new)")
        }
    }

    private fun RustWriter.deserializeString(target: StringShape) {
        when (target.hasTrait<EnumTrait>()) {
            true -> {
                if (returnSymbolToParse(target).isUnconstrained) {
                    rust("u.to_string()")
                } else {
                    rust("#T::from(u.to_string().as_ref())", symbolProvider.toSymbol(target))
                }
            }
            false -> rust("u.to_string()")
        }
    }

}
