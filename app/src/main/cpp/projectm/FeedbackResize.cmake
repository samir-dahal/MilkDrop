# Resize color feedback by resampling the previous texture before releasing it.
patch_projectm_source(Renderer src/libprojectM/Renderer/Framebuffer.cpp
    [=[    m_width = width;
    m_height = height;

    for (auto& attachments : m_attachments)]=]
    [=[    const int oldWidth = m_width;
    const int oldHeight = m_height;
    m_width = width;
    m_height = height;
    GLuint copyFramebuffer = 0;
    glGenFramebuffers(1, &copyFramebuffer);

    for (auto& attachments : m_attachments)]=]
    [=[            // Detach old texture, resize (destroys old and creates new), reattach new.]=]
    [=[            // Retain ownership until the resized attachment has received the old frame.
            const auto oldTexture = texture.second->Texture();
            // Detach old texture, resize (destroys old and creates new), reattach new.]=]
    [=[            glFramebufferTexture2D(GL_FRAMEBUFFER, texture.first, GL_TEXTURE_2D, texture.second->Texture()->TextureID(), 0);]=]
    [=[            glFramebufferTexture2D(GL_FRAMEBUFFER, texture.first, GL_TEXTURE_2D, texture.second->Texture()->TextureID(), 0);
            if (texture.first == GL_COLOR_ATTACHMENT0 && oldWidth > 0 && oldHeight > 0)
            {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, copyFramebuffer);
                glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, oldTexture->TextureID(), 0);
                glReadBuffer(GL_COLOR_ATTACHMENT0);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, m_framebufferIds.at(attachments.first));
                glBlitFramebuffer(0, 0, oldWidth, oldHeight, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_LINEAR);
                glBindFramebuffer(GL_FRAMEBUFFER, m_framebufferIds.at(attachments.first));
            }]=]
    [=[    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    return true;]=]
    [=[    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &copyFramebuffer);

    return true;]=]
)

# BeatDrop builds this frame's blur pyramid from the completed warp target.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
    [=[const auto warpedImage = m_framebuffer.GetColorAttachmentTexture(m_previousFrameBuffer, 0);]=]
    [=[const auto warpedImage = m_framebuffer.GetColorAttachmentTexture(m_currentFrameBuffer, 0);]=]
)
