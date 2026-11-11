include(${CMAKE_CURRENT_LIST_DIR}/ShaderRemainderAssignment.cmake)

# Disabled effects in legacy presets can divide a zero strength by a zero
# distance. Keep that contribution zero instead of poisoning the whole frame.
# Ordinary division, including nonzero numerators divided by zero, is unchanged.
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[            if ((m_version == Version_110 || m_version == Version_120 || m_version == Version_100_ES) && binaryExpression->binaryOp == HLSLBinaryOp_Mod) {]=]
    [=[            if (binaryExpression->binaryOp == HLSLBinaryOp_Div &&
                binaryExpression->expressionType.baseType >= HLSLBaseType_Float &&
                binaryExpression->expressionType.baseType <= HLSLBaseType_Float4) {
                m_writer.Write("milkdrop_divide(");
                OutputExpression(binaryExpression->expression1, &binaryExpression->expressionType);
                m_writer.Write(", ");
                OutputExpression(binaryExpression->expression2, &binaryExpression->expressionType);
                m_writer.Write(")");
            } else if ((m_version == Version_110 || m_version == Version_120 || m_version == Version_100_ES) && binaryExpression->binaryOp == HLSLBinaryOp_Mod) {]=]
    [=[    // Output the special function used to access rows in a matrix.]=]
    [=[    m_writer.WriteLine(0, "float milkdrop_divide(float x, float y) { return x == 0.0 && y == 0.0 ? 0.0 : x / y; }");
    for (int n = 2; n <= 4; ++n) {
        const std::string type = "vec" + std::to_string(n);
        std::string body = type + " milkdrop_divide(" + type + " x, " + type + " y) { return " + type + "(";
        for (int i = 0; i < n; ++i) {
            if (i) body += ",";
            const std::string channel(1, "xyzw"[i]);
            body += "milkdrop_divide(x." + channel + ",y." + channel + ")";
        }
        body += "); }";
        m_writer.WriteLine(0, "%s", body.c_str());
    }
    // Output the special function used to access rows in a matrix.]=]
)

# Parenthesized constructors are expressions, not C-style casts. Parse them via
# the regular expression path so postfix swizzles and indexing bind after ')'.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[        // Check for a casting operator.
        HLSLType type;]=]
    [=[        // Check for a casting operator.
        const char* expressionStart = m_tokenizer.getLastPos(false);
        HLSLType type;]=]
    [=[                needsExpressionEndChar = expressionEndChar;
                return ParsePartialConstructor(expression, type.baseType, type.typeName);
            }
            HLSLCastingExpression* castingExpression]=]
    [=[                m_tokenizer.ReturnToPos(expressionStart);
                m_tokenizer.Next();
            }
            else
            {
            HLSLCastingExpression* castingExpression]=]
    [=[            return Expect(')') && ParseExpression(castingExpression->expression);
        }]=]
    [=[            return Expect(')') && ParseExpression(castingExpression->expression);
            }
        }]=]
)

# Function-like macros substitute tokens, including multiple statements. Do not
# invent parentheses around their bodies; authors supply grouping when needed.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[    // Surround macro definition with parenthesis
    sourcePreprocessed.append("(");]=]
    [=[    // Preserve the function-like macro's original grouping.
    sourcePreprocessed.append(" ");]=]
    [=[    // Surround macro definition with parenthesis
    sourcePreprocessed.append(")");]=]
    [=[    // Separate the expansion from the following token.
    sourcePreprocessed.append(" ");]=]
)

# HLSL permits whitespace between '#' and a directive's name.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[    // Prepare next token
    m_tokenizer.Next();]=]
    [=[    // Empty object-like defines end here; do not consume the next line.
    m_tokenizer.Next(false);]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[        name = "mod";]=]
    [=[        name = "milkdrop_signed_remainder";]=]
    [=[            m_writer.Write("mod(");]=]
    [=[            m_writer.Write("milkdrop_signed_remainder(");]=]
    [=[    // Output the special function used to access rows in a matrix.]=]
    [=[    // HLSL remainder truncates toward zero; GLSL mod uses floor. Keep
    // operands as function parameters so side effects are evaluated only once.
    for (int n = 1; n <= 4; ++n)
    {
        const std::string type = n == 1 ? "float" : "vec" + std::to_string(n);
        m_writer.WriteLine(0, "%s milkdrop_signed_remainder(%s x, %s y) { return x - y * trunc(x / y); }", type.c_str(), type.c_str(), type.c_str());
        if (n > 1)
        {
            m_writer.WriteLine(0, "%s milkdrop_signed_remainder(%s x, float y) { return x - y * trunc(x / y); }", type.c_str(), type.c_str());
            m_writer.WriteLine(0, "%s milkdrop_signed_remainder(float x, %s y) { return x - y * trunc(x / y); }", type.c_str(), type.c_str());
        }
    }
    // Output the special function used to access rows in a matrix.]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTokenizer.cpp
    [=[    // Must be an identifier or a reserved word.]=]
    [=[    if (m_buffer[0] == '#')
    {
        const char* word = m_buffer + 1;
        while (word < m_bufferEnd && (*word == ' ' || *word == '\t')) ++word;
        const char* directives[] = {"define", "if", "else", "endif"};
        const int tokens[] = {HLSLToken_PreprocessorDefine, HLSLToken_PreprocessorIf,
                              HLSLToken_PreprocessorElse, HLSLToken_PreprocessorEndif};
        for (int i = 0; i < 4; ++i)
        {
            const size_t n = strlen(directives[i]);
            if (static_cast<size_t>(m_bufferEnd - word) >= n &&
                strncmp(word, directives[i], n) == 0 &&
                (word + n == m_bufferEnd || !isalnum(word[n]) && word[n] != '_'))
            {
                m_buffer = word + n;
                m_token = tokens[i];
                return;
            }
        }
    }
    // Must be an identifier or a reserved word.]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[return Expect(')') && ParseExpression(castingExpression->expression);]=]
    [=[// A cast binds to its unary/postfix operand, not the following comparison.
            return Expect(')') && ParseBinaryExpression(100, castingExpression->expression);]=]
    [=[        if (m_tokenizer.GetToken() != ';' && !ParseDeclaration(forStatement->initialization) && !ParseExpression(forStatement->initializationWithoutType))
        {
            return false;
        }
        if (!Expect(';'))
        {
            return false;
        }]=]
    [=[        if (ParseDeclaration(forStatement->initialization))
        {
            if (!Expect(';')) return false;
        }
        else
        {
            int count = 0;
            if (!ParseExpressionList(';', true, forStatement->initializationWithoutType, count)) return false;
        }]=]
    [=[ParseExpressionList(')', false, forStatement->increment, incrementCount)]=]
    [=[ParseExpressionList(')', true, forStatement->increment, incrementCount)]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[OutputExpression(forStatement->initializationWithoutType);]=]
    [=[OutputExpressionList(forStatement->initializationWithoutType);]=]
    [=[            m_writer.Write("clamp(");
            OutputExpression(argument[0]);]=]
    [=[            m_writer.Write("clamp(");
            if (argument[0]->expressionType.baseType == HLSLBaseType_Bool)
                OutputExpression(argument[0], &kFloatType);
            else
                OutputExpression(argument[0]);]=]
    [=[            case HLSLBaseType_Float:
            case HLSLBaseType_Float2:
            case HLSLBaseType_Float3:
            case HLSLBaseType_Float4:
                m_writer.Write(", 0.0, 1.0)");]=]
    [=[            case HLSLBaseType_Bool:
            case HLSLBaseType_Float:
            case HLSLBaseType_Float2:
            case HLSLBaseType_Float3:
            case HLSLBaseType_Float4:
                m_writer.Write(", 0.0, 1.0)");]=]
)
patch_projectm_copy(Renderer src/libprojectM/Renderer/TextureManager.cpp
    [=[    Preload();]=]
    [=[    m_extensions.emplace_back(".jfif");
    Preload();]=]
)

# HLSL flattens four float4 initializer values into a 16-element scalar array.
# Use a helper so every vector expression is evaluated exactly once.
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[        OutputDeclarationBody( declaration->type, GetSafeIdentifierName( declaration->name ) );

        OutputDeclarationAssignment(declaration);]=]
    [=[        // This is an assignment to an existing global, not an array declaration.
        OutputIdentifier(declaration->name);

        OutputDeclarationAssignment(declaration);]=]
    [=[    // Output the special function used to access rows in a matrix.]=]
    [=[    m_writer.WriteLine(0, "float[16] milkdrop_flatten_vec4_array(vec4 a, vec4 b, vec4 c, vec4 d) {");
    m_writer.WriteLine(1, "return float[16](a.x,a.y,a.z,a.w,b.x,b.y,b.z,b.w,c.x,c.y,c.z,c.w,d.x,d.y,d.z,d.w);");
    m_writer.WriteLine(0, "}");
    // Output the special function used to access rows in a matrix.]=]
    [=[       m_writer.Write( "%s[]( ", GetTypeName( declaration->type ) );]=]
    [=[       int elements = 0, vectorCount = 0;
       bool fourVectors = declaration->type.baseType == HLSLBaseType_Float &&
                          m_tree->GetExpressionValue(declaration->type.arraySize, elements) && elements == 16;
       for (auto* value = declaration->assignment; value; value = value->nextExpression)
       {
           ++vectorCount;
           fourVectors = fourVectors && value->expressionType.baseType == HLSLBaseType_Float4;
       }
       if (fourVectors && vectorCount == 4)
           m_writer.Write("milkdrop_flatten_vec4_array(");
       else
           m_writer.Write( "%s[]( ", GetTypeName( declaration->type ) );]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTree.cpp
    [=[    if (node->initializationWithoutType) VisitExpression(node->initializationWithoutType);]=]
    [=[    for (auto* init = node->initializationWithoutType; init; init = init->nextExpression)
        VisitExpression(init);]=]
)
