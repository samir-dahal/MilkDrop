# Extend the generated parser copy; leave the projectM submodule untouched.
# HLSL permits names that GLSL reserves; rename declarations and references together.
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.h
    [=[s_numReservedWords = 9 + 24;]=]
    [=[s_numReservedWords = 11 + 24;]=]
    [=[char                m_matrixRowFunction[64];]=]
    [=[char                m_matrixRowFunction[64];
    char                m_matrixSetRowFunction[64];]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[kFirstBuiltinFunctionWord = 9;]=]
    [=[kFirstBuiltinFunctionWord = 11;]=]
    [=["main",]=]
    [=["main", "smooth", "active",]=]
    [=[m_matrixRowFunction[0]      = 0;]=]
    [=[m_matrixRowFunction[0]      = 0;
    m_matrixSetRowFunction[0]   = 0;]=]
    [=[ChooseUniqueName("matrix_row", m_matrixRowFunction, sizeof(m_matrixRowFunction));]=]
    [=[ChooseUniqueName("matrix_row", m_matrixRowFunction, sizeof(m_matrixRowFunction));
    ChooseUniqueName("matrix_set_row", m_matrixSetRowFunction, sizeof(m_matrixSetRowFunction));]=]
    [=[    // Output the special function used to access rows in a matrix.]=]
    [=[    // HLSL indexes matrix rows; GLSL indexes columns. A row getter is not
    // an l-value, so whole-row assignments need a separate inout helper.
    // Return the stored row to preserve assignment-expression semantics.
    for (int columns = 2; columns <= 4; ++columns)
    {
        for (int rows = 2; rows <= 4; ++rows)
        {
            std::string matrixType = "mat" + std::to_string(columns);
            if (rows != columns) matrixType += "x" + std::to_string(rows);
            m_writer.Write("vec%d %s(inout %s m, int i, vec%d v) { ",
                           columns, m_matrixSetRowFunction, matrixType.c_str(), columns);
            for (int column = 0; column < columns; ++column)
            {
                m_writer.Write("m[%d][i] = v[%d]; ", column, column);
            }
            m_writer.WriteLine(0, "return v; }");
        }
    }

    // Output the special function used to access rows in a matrix.]=]
    [=[        if (binaryExpression->binaryOp == HLSLBinaryOp_Mod &&]=]
    [=[        HLSLArrayAccess* assignedRow =
            binaryExpression->expression1->nodeType == HLSLNodeType_ArrayAccess
                ? static_cast<HLSLArrayAccess*>(binaryExpression->expression1) : NULL;
        if (binaryExpression->binaryOp == HLSLBinaryOp_Assign && assignedRow &&
            !assignedRow->array->expressionType.array && IsMatrixType(assignedRow->array->expressionType.baseType))
        {
            const HLSLType intType(HLSLBaseType_Int);
            m_writer.Write("%s(", m_matrixSetRowFunction);
            OutputExpression(assignedRow->array);
            m_writer.Write(", ");
            OutputExpression(assignedRow->index, &intType);
            m_writer.Write(", ");
            OutputExpression(binaryExpression->expression2, &assignedRow->expressionType);
            m_writer.Write(")");
        }
        else if (binaryExpression->binaryOp == HLSLBinaryOp_Mod &&]=]
)
