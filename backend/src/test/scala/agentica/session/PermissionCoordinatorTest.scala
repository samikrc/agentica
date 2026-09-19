package agentica.permissions

import agentica.agent.AgentEvent
import org.scalatest.funsuite.AnyFunSuite
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.collection.mutable.ListBuffer

class PermissionCoordinatorTest extends AnyFunSuite
{
    private val granted = GrantDecision.Granted(GrantTTL.Once, None)

    test("decision completes one-shot and duplicate resolution is rejected") {
        val coordinator = new PermissionCoordinator("one-shot")
        var result: PermissionCoordinator.ResolveResult = PermissionCoordinator.ResolveResult.Unknown
        val decision = coordinator.request("files.write", None, Nil, {
            case AgentEvent.PermissionRequired(requestId, _, _, _) =>
                result = coordinator.resolve(requestId, granted)
            case _ => ()
        })
        assert(decision == granted)
        assert(result == PermissionCoordinator.ResolveResult.Completed)

        var duplicate = PermissionCoordinator.ResolveResult.Unknown
        coordinator.request("files.write", None, Nil, {
            case AgentEvent.PermissionRequired(requestId, _, _, _) =>
                assert(coordinator.resolve(requestId, granted) == PermissionCoordinator.ResolveResult.Completed)
                duplicate = coordinator.resolve(requestId, GrantDecision.Denied)
            case _ => ()
        })
        assert(duplicate == PermissionCoordinator.ResolveResult.AlreadyCompleted)
    }

    test("completion directly from onEvent is not lost") {
        val coordinator = new PermissionCoordinator("early")
        val decision = coordinator.request("files.write", None, Nil, {
            case AgentEvent.PermissionRequired(requestId, _, _, _) =>
                coordinator.resolve(requestId, granted)
            case _ => ()
        })
        assert(decision == granted)
    }

    test("unknown request is reported") {
        val coordinator = new PermissionCoordinator("unknown")
        assert(coordinator.resolve("missing", granted) == PermissionCoordinator.ResolveResult.Unknown)
    }

    test("two sequential requests use distinct IDs and resolve independently") {
        val coordinator = new PermissionCoordinator("sequential")
        val ids = ListBuffer.empty[String]
        val decisions = List(granted, GrantDecision.Denied).map { expected =>
            coordinator.request("files.write", None, Nil, {
                case AgentEvent.PermissionRequired(requestId, _, _, _) =>
                    ids += requestId
                    coordinator.resolve(requestId, expected)
                case _ => ()
            })
        }
        assert(ids.distinct.size == 2)
        assert(decisions == List(granted, GrantDecision.Denied))
    }

    test("timeout safely yields Denied") {
        val coordinator = new PermissionCoordinator("timeout", 1L, TimeUnit.MILLISECONDS)
        assert(coordinator.request("files.write", None, Nil, _ => ()) == GrantDecision.Denied)
    }

    test("close unblocks a pending request with Denied") {
        val coordinator = new PermissionCoordinator("close")
        val emitted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try
        {
            val result = executor.submit(() => coordinator.request("files.write", None, Nil, _ => emitted.countDown()))
            assert(emitted.await(5, TimeUnit.SECONDS))
            coordinator.close()
            assert(result.get(5, TimeUnit.SECONDS) == GrantDecision.Denied)
        }
        finally executor.shutdownNow()
    }

    test("request after close returns Denied and emits no event") {
        val coordinator = new PermissionCoordinator("after-close")
        coordinator.close()
        var emitted = false
        val decision = coordinator.request("files.write", None, Nil, _ => emitted = true)
        assert(decision == GrantDecision.Denied)
        assert(!emitted)
    }
}
