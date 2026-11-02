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
    [=[    if (node->increment) {
        VisitExpression(node->increment);
    }]=]
    [=[    for (auto* increment = node->increment; increment; increment = increment->nextExpression) {
        VisitExpression(increment);
    }
    if (node->initializationWithoutType) VisitExpression(node->initializationWithoutType);]=]
    [=[if (NeedsFlattening(node->initialization->assignment, 1))]=]
    [=[if (node->initialization && NeedsFlattening(node->initialization->assignment, 1))]=]
)

# Raymarching presets use for(; condition; step1, step2). The upstream parser
# required an initializer and accepted only one increment expression.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[if (!ParseDeclaration(forStatement->initialization) && !ParseExpression(forStatement->initializationWithoutType))]=]
    [=[if (m_tokenizer.GetToken() != ';' && !ParseDeclaration(forStatement->initialization) && !ParseExpression(forStatement->initializationWithoutType))]=]
    [=[        ParseExpression(forStatement->condition);]=]
    [=[        if (m_tokenizer.GetToken() != ';' && !ParseExpression(forStatement->condition)) return false;]=]
    [=[        ParseExpression(forStatement->increment);
        if (!Expect(')'))]=]
    [=[        int incrementCount = 0;
        if (!ParseExpressionList(')', false, forStatement->increment, incrementCount))]=]
)

# Legacy D3D presets frequently use uninitialized numeric accumulators as zero.
include(${CMAKE_CURRENT_LIST_DIR}/ShaderLanguageCompatibility.cmake)
# Make that behavior explicit instead of depending on driver register contents.
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[void GLSLGenerator::OutputExpression(HLSLExpression* expression, const HLSLType* dstType)
{]=]
    [=[void GLSLGenerator::OutputExpression(HLSLExpression* expression, const HLSLType* dstType)
{
    if (!expression) return; // Empty for-loop clauses have no expression.]=]
    [=[OutputExpression(forStatement->increment);]=]
    [=[OutputExpressionList(forStatement->increment);]=]
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
