package ru.servermine.economy.internal;

import org.junit.jupiter.api.Test;
import ru.servermine.economy.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationRaceTest {
    @Test void losingReleaseDoesNotScheduleSecondInventoryPayout() {
        UUID id = UUID.randomUUID();
        OperationRecord reserved = row(id, OperationState.RESERVED);
        OperationRecord releasing = row(id, OperationState.RELEASING);
        var operations = mock(OperationRepository.class);
        var mainThread = mock(MainThread.class);
        when(operations.find(id)).thenReturn(CompletableFuture.completedFuture(Optional.of(reserved)));
        when(operations.tryTransition(eq(id), eq(OperationState.RESERVED), eq(OperationState.RELEASING), any(), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(new OperationRepository.TransitionResult(true, releasing)))
                .thenReturn(CompletableFuture.completedFuture(new OperationRepository.TransitionResult(false, releasing)));
        when(mainThread.call(any())).thenReturn(new CompletableFuture<>());
        var service = new EconomyServiceImpl(mock(CurrencyRegistry.class), mock(InventoryMoneyEngine.class), operations, mainThread);
        service.setReady(true);
        var first = service.release(id);
        var second = service.release(id).toCompletableFuture().join();
        assertFalse(first.toCompletableFuture().isDone());
        assertEquals(ResultCode.INVALID_OPERATION_STATE, second.code());
        verify(mainThread, times(1)).call(any());
    }
    @Test void commitDoesNotReportSuccessWhenReleaseWonTheRace() {
        UUID id = UUID.randomUUID();
        var operations = mock(OperationRepository.class);
        when(operations.find(id)).thenReturn(CompletableFuture.completedFuture(Optional.of(row(id, OperationState.RESERVED))));
        when(operations.tryTransition(eq(id), eq(OperationState.RESERVED), eq(OperationState.COMMITTED), any(), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(new OperationRepository.TransitionResult(false, row(id, OperationState.RELEASING))));
        var service = new EconomyServiceImpl(mock(CurrencyRegistry.class), mock(InventoryMoneyEngine.class), operations, mock(MainThread.class));
        service.setReady(true);
        assertEquals(ResultCode.INVALID_OPERATION_STATE, service.commit(id).toCompletableFuture().join().code());
    }
    private OperationRecord row(UUID id, OperationState state) {
        return new OperationRecord(id, "RESERVE", UUID.randomUUID(), 750, "Cities", "claim", state,
                ResultCode.OK, 1000, 250, Instant.now(), Instant.now());
    }
}
