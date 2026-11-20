# Copy modified uniform banks before use. Otherwise a compound assignment such
# as q31 *= .35 reads uninitialized memory and loses the other bank components.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTree.h
    [=[        initialization = NULL;
        condition = NULL;]=]
    [=[        initialization = NULL;
        initializationWithoutType = NULL;
        condition = NULL;]=]
)
patch_projectm_source(hlslparser vendor/hlslparser/src/HLSLTree.cpp
    [=[            // Declare uniforms replacements]=]
    [=[            // Rename reads that precede an assignment too (loops/branches).
            HLSLTreeVisitor::VisitFunction(node);

            // Declare uniforms replacements]=]
    [=[                declaration->type = uniformDeclaration->type;]=]
    [=[                declaration->type = uniformDeclaration->type;
                declaration->type.flags &= ~HLSLTypeFlag_Uniform;
                auto* initialValue = tree->AddNode<HLSLIdentifierExpression>(node->fileName, node->line);
                initialValue->name = uniformDeclaration->name;
                initialValue->global = true;
                initialValue->expressionType = uniformDeclaration->type;
                declaration->assignment = initialValue;]=]
    [=[if (NeedsFlattening(node->initialization->assignment, 1))]=]
    [=[if (node->initialization && NeedsFlattening(node->initialization->assignment, 1))]=]
)

# Retain upstream's increment-list traversal when it is already present.
file(READ "${PROJECTM_SOURCE_DIR}/vendor/hlslparser/src/HLSLTree.cpp" HLSL_TREE_SOURCE)
string(FIND "${HLSL_TREE_SOURCE}" "HLSLExpression * increment = node->increment;" HLSL_INCREMENT_LIST)
if(NOT HLSL_INCREMENT_LIST EQUAL -1)
    patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTree.cpp
        [=[        increment = increment->nextExpression;
    }
    VisitStatements(node->statement);]=]
        [=[        increment = increment->nextExpression;
    }
    if (node->initializationWithoutType) VisitExpression(node->initializationWithoutType);
    VisitStatements(node->statement);]=]
    )
else()
    patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTree.cpp
    [=[    if (node->increment) {
        VisitExpression(node->increment);
    }]=]
    [=[    for (auto* increment = node->increment; increment; increment = increment->nextExpression) {
        VisitExpression(increment);
    }
    if (node->initializationWithoutType) VisitExpression(node->initializationWithoutType);]=]
    )
endif()

# Raymarching presets use for(; condition; step1, step2). The upstream parser
# required an initializer and accepted only one increment expression.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[if (!ParseDeclaration(forStatement->initialization) && !ParseExpression(forStatement->initializationWithoutType))]=]
    [=[if (m_tokenizer.GetToken() != ';' && !ParseDeclaration(forStatement->initialization) && !ParseExpression(forStatement->initializationWithoutType))]=]
    [=[        ParseExpression(forStatement->condition);]=]
    [=[        if (m_tokenizer.GetToken() != ';' && !ParseExpression(forStatement->condition)) return false;]=]
)

# Route both upstream loop parsers through MilkDropâ€™s expression-list handling.
file(READ "${PROJECTM_SOURCE_DIR}/vendor/hlslparser/src/HLSLParser.cpp" HLSL_PARSER_SOURCE)
string(FIND "${HLSL_PARSER_SOURCE}" "if (ParseExpression(forStatement->increment))" HLSL_LIST_PARSE)
if(NOT HLSL_LIST_PARSE EQUAL -1)
    patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
        [=[        if (ParseExpression(forStatement->increment))
        {
            // The increment may be a comma-separated list of expressions,
            // e.g. "i++, j++". Chain them like an expression list.
            HLSLExpression* lastExpression = forStatement->increment;
            while (Accept(','))
            {
                HLSLExpression* expression = NULL;
                if (!ParseExpression(expression))
                {
                    return false;
                }
                lastExpression->nextExpression = expression;
                lastExpression = expression;
            }
        }
        if (!Expect(')'))]=]
        [=[        int incrementCount = 0;
        if (!ParseExpressionList(')', false, forStatement->increment, incrementCount))]=]
    )
else()
    patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[        ParseExpression(forStatement->increment);
        if (!Expect(')'))]=]
    [=[        int incrementCount = 0;
        if (!ParseExpressionList(')', false, forStatement->increment, incrementCount))]=]
    )
endif()
file(READ "${PROJECTM_SOURCE_DIR}/vendor/hlslparser/src/GLSLGenerator.cpp" HLSL_GENERATOR_SOURCE)
string(FIND "${HLSL_GENERATOR_SOURCE}" "OutputExpressionList(forStatement->increment);" HLSL_LIST_OUTPUT)
if(HLSL_LIST_OUTPUT EQUAL -1)
    patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
        [=[OutputExpression(forStatement->increment);]=]
        [=[OutputExpressionList(forStatement->increment);]=]
    )
endif()

# Legacy D3D presets frequently use uninitialized numeric accumulators as zero.
include(${CMAKE_CURRENT_LIST_DIR}/ShaderLanguageCompatibility.cmake)
# Make that behavior explicit instead of depending on driver register contents.
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[void GLSLGenerator::OutputExpression(HLSLExpression* expression, const HLSLType* dstType)
{]=]
    [=[void GLSLGenerator::OutputExpression(HLSLExpression* expression, const HLSLType* dstType)
{
    if (!expression) return; // Empty for-loop clauses have no expression.]=]
    [=[		lastDecl = declaration;
		declaration = declaration->nextDeclaration;]=]
    [=[        if (!declaration->assignment && !skipAssignment && !declaration->type.array &&
            !(declaration->type.flags & HLSLTypeFlag_Uniform) &&
            declaration->type.baseType >= HLSLBaseType_FirstNumeric &&
            declaration->type.baseType <= HLSLBaseType_LastNumeric)
        {
            m_writer.Write(" = %s(0)", GetTypeName(declaration->type));
        }
		lastDecl = declaration;
		declaration = declaration->nextDeclaration;]=]
)
