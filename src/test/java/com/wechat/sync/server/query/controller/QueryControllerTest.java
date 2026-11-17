package com.wechat.sync.server.query.controller;

import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.query.dto.CursorPageVO;
import com.wechat.sync.server.query.dto.QueryVOs;
import com.wechat.sync.server.query.dto.SearchRequest;
import com.wechat.sync.server.query.service.QueryService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** 查询端点：类级 admin 防线 + 路由接线 + 代表端点转发（不起 Spring 上下文，鉴权拦截器属框架行为不测）。 */
@ExtendWith(MockitoExtension.class)
class QueryControllerTest {

    @Mock private QueryService queryService;

    private QueryController controller;

    @BeforeEach
    void setUp() { controller = new QueryController(queryService); }

    /** §5 全部 admin：类级 @RequiresToken(value=admin, roles=admin)；cursor 与 offset 两条路由并存。 */
    @Test
    void classDeclaresAdminGuardAndRepresentativeRoutes() throws NoSuchMethodException {
        RequiresToken guard = QueryController.class.getAnnotation(RequiresToken.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo(TokenService.ADMIN_TYPE);
        assertThat(guard.roles()).containsExactly(TokenService.ROLE_ADMIN);

        Method messages = QueryController.class.getMethod("messages", String.class, String.class,
                String.class, String.class, String.class, String.class, Integer.class);
        assertThat(messages.getAnnotation(GetMapping.class).value()).containsExactly("/v1/messages");

        Method search = QueryController.class.getMethod("search", SearchRequest.class);
        assertThat(search.getAnnotation(PostMapping.class).value()).containsExactly("/v1/messages/search");
        assertThat(search.getParameterAnnotations()[0]).anySatisfy(a ->
                assertThat(a).isInstanceOf(Valid.class));

        Method chats = QueryController.class.getMethod("chats", String.class, Boolean.class,
                long.class, long.class);
        assertThat(chats.getAnnotation(GetMapping.class).value()).containsExactly("/v1/chats");
    }

    /** 代表端点转发：GET /v1/messages 七参全透传 + GET /v1/chats 分页透传，同一实例进 success 信封。 */
    @Test
    void forwardsMessagesAndChatsParamsIntoSuccessEnvelope() {
        CursorPageVO<QueryVOs.MessageVO> cursorPage = new CursorPageVO<>(List.of(), "cur", true);
        given(queryService.messages("chat", "after", "before", "sender", "type", "cur", 5))
                .willReturn(cursorPage);

        ApiResponse<CursorPageVO<QueryVOs.MessageVO>> messages =
                controller.messages("chat", "after", "before", "sender", "type", "cur", 5);

        assertThat(messages.isSuccess()).isTrue();
        assertThat(messages.getCode()).isZero();
        assertThat(messages.getData()).isSameAs(cursorPage);
        assertThat(messages.getData().nextCursor()).isEqualTo("cur");
        verify(queryService).messages("chat", "after", "before", "sender", "type", "cur", 5);

        PageVO<QueryVOs.ChatVO> chatPage = new PageVO<>(List.of(), 0, 1, 20, false);
        given(queryService.chats("wxid_a", Boolean.TRUE, 2, 50)).willReturn(chatPage);

        ApiResponse<PageVO<QueryVOs.ChatVO>> chats = controller.chats("wxid_a", Boolean.TRUE, 2, 50);

        assertThat(chats.isSuccess()).isTrue();
        assertThat(chats.getData()).isSameAs(chatPage);
        verify(queryService).chats("wxid_a", Boolean.TRUE, 2, 50);
    }
}
