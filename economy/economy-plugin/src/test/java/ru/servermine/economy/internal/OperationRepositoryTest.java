package ru.servermine.economy.internal;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.servermine.economy.api.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationRepositoryTest {
    @TempDir Path directory;
    ExecutorService executor;
    OperationRepository repository;
    @BeforeEach void setup() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        repository = new OperationRepository(directory.resolve("test.db"), 5000, executor);
        repository.initialize(true);
    }
    @AfterEach void close() { executor.close(); }
    MoneyRequest request() { return new MoneyRequest(UUID.randomUUID(), UUID.randomUUID(), 750, "Cities", "claim"); }
    @Test void identicalNormalizedRequestIsIdempotent() {
        var request = request();
        assertEquals(OperationRepository.PrepareStatus.CREATED, repository.prepare("RESERVE", request).join().status());
        repository.transition(request.operationId(), OperationState.PREPARED, OperationState.RESERVED, ResultCode.OK, 1000, 250).join();
        var repeat = new MoneyRequest(request.operationId(), request.playerId(), 750, " Cities ", " claim ");
        var replay = repository.prepare("RESERVE", repeat).join();
        assertEquals(OperationRepository.PrepareStatus.EXISTING_SAME, replay.status());
        assertEquals(OperationState.RESERVED, replay.record().state());
        assertEquals(250, replay.record().balanceAfter());
    }
    @Test void conflictingParametersNeverReplaceOriginal() {
        var r = request();
        repository.prepare("RESERVE", r).join();
        for (var conflict : List.of(
                new MoneyRequest(r.operationId(), r.playerId(), 751, r.sourcePlugin(), r.purpose()),
                new MoneyRequest(r.operationId(), UUID.randomUUID(), r.amount(), r.sourcePlugin(), r.purpose()),
                new MoneyRequest(r.operationId(), r.playerId(), r.amount(), "Other", r.purpose()),
                new MoneyRequest(r.operationId(), r.playerId(), r.amount(), r.sourcePlugin(), "other"))) {
            assertEquals(OperationRepository.PrepareStatus.CONFLICT, repository.prepare("RESERVE", conflict).join().status());
        }
        assertEquals(OperationRepository.PrepareStatus.CONFLICT, repository.prepare("PAYOUT", r).join().status());
        assertEquals(750, repository.find(r.operationId()).join().orElseThrow().amount());
    }
    @Test void concurrentDuplicatePreparationHasOneWinner() {
        var r = request();
        var futures = java.util.stream.IntStream.range(0, 24).mapToObj(i -> repository.prepare("RESERVE", r)).toList();
        assertEquals(1, futures.stream().map(CompletableFuture::join).filter(p -> p.status() == OperationRepository.PrepareStatus.CREATED).count());
    }
    @Test void releaseTransitionHasOneOwner() {
        var r = request();
        repository.prepare("RESERVE", r).join();
        repository.transition(r.operationId(), OperationState.PREPARED, OperationState.RESERVED, ResultCode.OK, 1000, 250).join();
        var futures = java.util.stream.IntStream.range(0, 24).mapToObj(i -> repository.tryTransition(r.operationId(),
                OperationState.RESERVED, OperationState.RELEASING, ResultCode.OK, 1000, 250)).toList();
        assertEquals(1, futures.stream().map(CompletableFuture::join).filter(OperationRepository.TransitionResult::applied).count());
    }
    @Test void restartMarksAmbiguousOperationsUnknownButPreservesReservations() throws Exception {
        var prepared = request(); var releasing = request(); var reserved = request();
        for (var r : List.of(prepared, releasing, reserved)) repository.prepare("RESERVE", r).join();
        repository.transition(releasing.operationId(), OperationState.PREPARED, OperationState.RELEASING, ResultCode.OK, 1000, 250).join();
        repository.transition(reserved.operationId(), OperationState.PREPARED, OperationState.RESERVED, ResultCode.OK, 1000, 250).join();
        repository.initialize(true);
        assertEquals(OperationState.UNKNOWN, repository.find(prepared.operationId()).join().orElseThrow().state());
        assertEquals(OperationState.UNKNOWN, repository.find(releasing.operationId()).join().orElseThrow().state());
        assertEquals(OperationState.RESERVED, repository.find(reserved.operationId()).join().orElseThrow().state());
    }
}
