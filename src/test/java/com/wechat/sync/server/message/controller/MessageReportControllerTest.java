package com.wechat.sync.server.message.controller;

import com.wechat.sync.server.auth.AccountBinding;
import com.wechat.sync.server.message.dto.MessagesIngestVO;
import com.wechat.sync.server.message.dto.MessagesReportRequest;
import com.wechat.sync.server.message.service.MessagesIngestService;
import fun.commons.framework4j.web.ApiError;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

/** 信封取值（API_DESIGN §4.2）：0 / 10700（data+error 并存）/ 10100（data=null），绑定校验先于落库。 */
@ExtendWith(MockitoExtension.class)
class MessageReportControllerTest {

    private static final String ACCOUNT = "wxid_t4unit_a";
    private static final String SHA = "0000000000000000000000000000000000000000000000000000000000000001";

    @Mock private MessagesIngestService ingestService;
    @Mock private AccountBinding accountBinding;

    private MessageReportController controller;

    @BeforeEach
    void setUp() { controller = new MessageReportController(ingestService, accountBinding); }

    private MessagesReportRequest request() {
        return new MessagesReportRequest(ACCOUNT, null, List.of(new MessagesReportRequest.ChatItem(
                "t4unit_room@chatroom", null, List.of(new MessagesReportRequest.MessageItem("db/MSG0.db", 10L,
                "12345", null, 1760000000000L, 1760000000L, "10000", "文本", null, "SHARD_NAME2ID", "hi", null,
                SHA, Boolean.FALSE, null)))));
    }

    private MessagesIngestVO vo(int resultCount) {
        List<MessagesIngestVO.MessageResult> results = IntStream.range(0, resultCount).mapToObj(
                i -> new MessagesIngestVO.MessageResult("db/MSG0.db", 10L, "srv:12345", "101", "inserted")).toList();
        return new MessagesIngestVO("1000", results, List.of());
    }

    @Test
    void allValidBatchReturnsZeroEnvelope() {
        MessagesReportRequest req = request();
        given(ingestService.requireAccount(ACCOUNT)).willReturn(ACCOUNT);
        given(ingestService.ingest(req))
                .willReturn(new MessagesIngestService.IngestOutcome(List.of(), vo(1)));

        ApiResponse<MessagesIngestVO> resp = controller.report(req);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getError()).isNull();
        assertThat(resp.getData().results()).hasSize(1);
        assertThat(resp.getData().batchId()).isEqualTo("1000");
        verify(accountBinding).requireRegistered(ACCOUNT);
    }

    @Test
    void partialFailureReturns10700WithDataAndErrorsTogether() {
        MessagesReportRequest req = request();
        given(ingestService.requireAccount(ACCOUNT)).willReturn(ACCOUNT);
        given(ingestService.ingest(req)).willReturn(new MessagesIngestService.IngestOutcome(
                List.of(ApiError.of("chats[0].messages[1].content_sha256", "FORMAT_INVALID", "须 64 位十六进制", "xx")),
                vo(1)));

        ApiResponse<MessagesIngestVO> resp = controller.report(req);

        assertThat(resp.getCode()).isEqualTo(10700);
        assertThat(resp.getData()).isNotNull();
        assertThat(resp.getData().results()).hasSize(1);
        assertThat(resp.getError()).hasSize(1);
        assertThat(resp.getError().get(0).field()).isEqualTo("chats[0].messages[1].content_sha256");
    }

    @Test
    void noValidEntryAtAllThrows10100WithDataNull() {
        MessagesReportRequest req = request();
        given(ingestService.requireAccount(ACCOUNT)).willReturn(ACCOUNT);
        given(ingestService.ingest(req)).willReturn(new MessagesIngestService.IngestOutcome(
                List.of(ApiError.of("chats[0].messages[0].shard", "FORMAT_INVALID", "shard 必填", null)), vo(0)));

        assertThatThrownBy(() -> controller.report(req))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));
    }

    @Test
    void bindingCheckRunsOnTrimmedAccountBeforeIngest() {
        MessagesReportRequest req = new MessagesReportRequest("  " + ACCOUNT + "  ", null, List.of());
        given(ingestService.requireAccount("  " + ACCOUNT + "  ")).willReturn(ACCOUNT);
        given(ingestService.ingest(any())).willReturn(new MessagesIngestService.IngestOutcome(List.of(), vo(0)));

        assertThatThrownBy(() -> controller.report(req))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10100));

        InOrder order = inOrder(accountBinding, ingestService);
        order.verify(accountBinding).requireRegistered(ACCOUNT);
        order.verify(ingestService).ingest(req);
    }
}
