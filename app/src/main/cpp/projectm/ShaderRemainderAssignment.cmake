# Keep %= as an assignment in the AST. An inout helper evaluates indexed and
# swizzled lvalues once, and preserves HLSL's signed floating remainder.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTokenizer.h
    "    HLSLToken_DivideEqual," "    HLSLToken_DivideEqual,\n    HLSLToken_ModEqual,")
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTokenizer.cpp
    [=[    else if (m_buffer[0] == '/' && m_buffer[1] == '=')]=]
    [=[    else if (m_buffer[0] == '%' && m_buffer[1] == '=')
    {
        m_token = HLSLToken_ModEqual;
        m_buffer += 2;
        return;
    }
    else if (m_buffer[0] == '/' && m_buffer[1] == '=')]=])
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTree.h
    "    HLSLBinaryOp_DivAssign," "    HLSLBinaryOp_DivAssign,\n    HLSLBinaryOp_ModAssign,"
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[    else if (Accept(HLSLToken_DivideEqual))]=]
    [=[    else if (Accept(HLSLToken_ModEqual))
    {
        binaryOp = HLSLBinaryOp_ModAssign;
    }
    else if (Accept(HLSLToken_DivideEqual))]=])
# Compile the tree visitors against the extended operator enum as well, so
# dead-expression elimination recognizes the write performed by %=.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTree.cpp
    "case HLSLBinaryOp_DivAssign:" "case HLSLBinaryOp_ModAssign:\n            case HLSLBinaryOp_DivAssign:"
    "IsAssignOp(node->binaryOp)" "(IsAssignOp(node->binaryOp) || node->binaryOp == HLSLBinaryOp_ModAssign)"
    "IsAssignOp(binaryExpr->binaryOp)" "(IsAssignOp(binaryExpr->binaryOp) || binaryExpr->binaryOp == HLSLBinaryOp_ModAssign)")
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[case HLSLBinaryOp_DivAssign:    op = " /= ";]=]
    [=[case HLSLBinaryOp_ModAssign:    op = ""; dstType2 = &binaryExpression->expressionType; break;
            case HLSLBinaryOp_DivAssign:    op = " /= ";]=]
    [=[                bool handled = false;]=]
    [=[                bool handled = false;
                if (binaryExpression->binaryOp == HLSLBinaryOp_ModAssign) {
                    m_writer.Write("milkdrop_mod_assign(");
                    OutputExpression(binaryExpression->expression1);
                    m_writer.Write(", ");
                    OutputExpression(binaryExpression->expression2, &binaryExpression->expressionType);
                    m_writer.Write(")");
                    handled = true;
                }]=]
    [=[            OutputExpression(expressionStatement->expression);
            m_writer.EndLine(";");]=]
    [=[            HLSLBinaryExpression* remainder = expressionStatement->expression->nodeType == HLSLNodeType_BinaryExpression
                ? static_cast<HLSLBinaryExpression*>(expressionStatement->expression) : NULL;
            HLSLArrayAccess* element = remainder && remainder->binaryOp == HLSLBinaryOp_ModAssign &&
                remainder->expression1->nodeType == HLSLNodeType_ArrayAccess
                ? static_cast<HLSLArrayAccess*>(remainder->expression1) : NULL;
            if (element) {
                // ANGLE may evaluate an inout subscript again during copy-out.
                // Materialize it before the call, including any ++ side effect.
                m_writer.Write("{ int milkdrop_mod_index = int(");
                OutputExpression(element->index);
                m_writer.Write("); milkdrop_mod_assign(");
                OutputExpression(element->array);
                m_writer.Write("[milkdrop_mod_index], ");
                OutputExpression(remainder->expression2, &remainder->expressionType);
                m_writer.EndLine("); }");
            } else {
                OutputExpression(expressionStatement->expression);
                m_writer.EndLine(";");
            }]=]
    [=[    // Output the special function used to access rows in a matrix.]=]
    [=[    for (int n = 1; n <= 4; ++n) {
        const std::string f = n == 1 ? "float" : "vec" + std::to_string(n);
        m_writer.WriteLine(0, "%s milkdrop_mod_assign(inout %s x, %s y) { x = x - y * trunc(x / y); return x; }", f.c_str(), f.c_str(), f.c_str());
        for (const std::string base : {"int", "uint"}) {
            const std::string t = n == 1 ? base : (base == "int" ? "ivec" : "uvec") + std::to_string(n);
            m_writer.WriteLine(0, "%s milkdrop_mod_assign(inout %s x, %s y) { x = x %% y; return x; }", t.c_str(), t.c_str(), t.c_str());
        }
    }
    // Output the special function used to access rows in a matrix.]=])
