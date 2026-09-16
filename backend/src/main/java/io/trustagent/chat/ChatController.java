package io.trustagent.chat;

import io.trustagent.chat.dto.ChatDtos;
import io.trustagent.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /** Agent Playground 核心接口。需要 {@code agent:chat} 权限（ADMIN / DEVELOPER）。 */
    @PostMapping("/{id}/chat")
    @PreAuthorize("hasAuthority('agent:chat')")
    public ApiResponse<ChatDtos.ChatResponse> chat(@PathVariable Long id,
                                                   @Valid @RequestBody ChatDtos.ChatRequest request) {
        return ApiResponse.ok(chatService.chat(id, request));
    }
}
