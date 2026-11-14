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

# Keep the upstream blur source: the previous feedback image, including its
# shapes and waves. BeatDrop's BlurPasses also samples VS0 (the input to warp),
# even though its comment calls this the current post-warp frame. Sampling the
# new warp target instead erases the blur signal in presets with `ret = 0`.
