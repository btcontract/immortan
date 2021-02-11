package immortan

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import immortan.ChannelMaster._
import immortan.PaymentStatus._
import immortan.PaymentFailure._
import fr.acinq.eclair.channel._
import scala.concurrent.duration._
import com.softwaremill.quicklens._
import fr.acinq.eclair.router.Router._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import immortan.utils.{Denomination, Rx, ThrottledWork}
import immortan.ChannelListener.{Malfunction, Transition}
import immortan.crypto.{CanBeRepliedTo, StateMachine, Tools}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import fr.acinq.eclair.router.Graph.GraphStructure.{DescAndCapacity, GraphEdge}
import fr.acinq.eclair.crypto.Sphinx.{DecryptedPacket, FailurePacket, PacketAndSecrets}
import immortan.Channel.{OPEN, SLEEPING, SUSPENDED, isOperational, isOperationalAndOpen}
import fr.acinq.eclair.transactions.{RemoteReject, RemoteUpdateFail, RemoteUpdateMalform}
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.Announcements
import java.util.concurrent.Executors
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.crypto.Sphinx
import scala.util.Random.shuffle
import rx.lang.scala.Observable
import scala.collection.mutable
import scodec.bits.ByteVector


object PaymentFailure {
  type Failures = List[PaymentFailure]
  final val NOT_ENOUGH_CAPACITY = "not-enough-capacity"
  final val RUN_OUT_OF_RETRY_ATTEMPTS = "run-out-of-retry-attempts"
  final val PEER_COULD_NOT_PARSE_ONION = "peer-could-not-parse-onion"
  final val NOT_RETRYING_NO_DETAILS = "not-retrying-no-details"
}

sealed trait PaymentFailure {
  def asString(denom: Denomination): String
}

case class LocalFailure(status: String, amount: MilliSatoshi) extends PaymentFailure {
  override def asString(denom: Denomination): String = s"-- LocalFailure: $status"
}

case class UnreadableRemoteFailure(route: Route) extends PaymentFailure {
  override def asString(denom: Denomination): String = s"-- RemoteFailure @ unknown-channel: ${route asString denom}"
}

case class RemoteFailure(packet: Sphinx.DecryptedFailurePacket, route: Route) extends PaymentFailure {
  def originChannelId: String = route.getEdgeForNode(packet.originNode).map(_.updExt.update.shortChannelId.toString).getOrElse("Trampoline")
  override def asString(denom: Denomination): String = s"-- ${packet.failureMessage.message} @ $originChannelId: ${route asString denom}"
}


sealed trait PartStatus { me =>
  final val partId: ByteVector = onionPrivKey.publicKey.value
  def tuple: (ByteVector, PartStatus) = (partId, me)
  def onionPrivKey: PrivateKey
}

case class InFlightInfo(cmd: CMD_ADD_HTLC, route: Route)

case class WaitForBetterConditions(onionPrivKey: PrivateKey, amount: MilliSatoshi) extends PartStatus

case class WaitForRouteOrInFlight(onionPrivKey: PrivateKey, amount: MilliSatoshi, cnc: ChanAndCommits, flight: Option[InFlightInfo] = None, localFailed: List[Channel] = Nil, remoteAttempts: Int = 0) extends PartStatus {
  def oneMoreRemoteAttempt(cnc1: ChanAndCommits): WaitForRouteOrInFlight = copy(flight = None, remoteAttempts = remoteAttempts + 1, cnc = cnc1)
  def oneMoreLocalAttempt(cnc1: ChanAndCommits): WaitForRouteOrInFlight = copy(flight = None, localFailed = localFailedChans, cnc = cnc1)
  lazy val localFailedChans: List[Channel] = cnc.chan :: localFailed
}

case class PaymentSenderData(cmd: CMD_SEND_MPP, parts: Map[ByteVector, PartStatus], failures: Failures = Nil) {
  def withRemoteFailure(route: Route, pkt: Sphinx.DecryptedFailurePacket): PaymentSenderData = copy(failures = RemoteFailure(pkt, route) +: failures)
  def withLocalFailure(reason: String, amount: MilliSatoshi): PaymentSenderData = copy(failures = LocalFailure(reason, amount) +: failures)
  def withoutPartId(partId: ByteVector): PaymentSenderData = copy(parts = parts - partId)

  def inFlights: Iterable[InFlightInfo] = parts.values.flatMap { case wait: WaitForRouteOrInFlight => wait.flight case _ => None }
  def successfulUpdates: Iterable[ChannelUpdate] = inFlights.flatMap(_.route.routedPerChannelHop).map { case (_, chanHop) => chanHop.edge.updExt.update }
  def closestCltvExpiry: InFlightInfo = inFlights.minBy(_.route.weight.cltv)
  def totalFee: MilliSatoshi = inFlights.map(_.route.fee).sum

  def asString(denom: Denomination): String = {
    val failuresByAmount: Map[String, Failures] = failures.groupBy {
      case fail: UnreadableRemoteFailure => denom.asString(fail.route.weight.costs.head)
      case fail: RemoteFailure => denom.asString(fail.route.weight.costs.head)
      case fail: LocalFailure => denom.asString(fail.amount)
    }

    val usedRoutes = inFlights.map(_.route asString denom).mkString("\n\n")
    def translateFailures(failureList: Failures): String = failureList.map(_ asString denom).mkString("\n\n")
    val errors = failuresByAmount.mapValues(translateFailures).map { case (amount, fails) => s"- $amount:\n\n$fails" }.mkString("\n\n")
    s"Routes:\n\n$usedRoutes\n\n\n\nErrors:\n\n$errors"
  }
}

case class SplitIntoHalves(amount: MilliSatoshi)
case class NodeFailed(failedNodeId: PublicKey, increment: Int)
case class ChannelFailed(failedDescAndCap: DescAndCapacity, increment: Int)

case class CMD_SEND_MPP(paymentHash: ByteVector32, targetNodeId: PublicKey, totalAmount: MilliSatoshi = 0L.msat,
                        paymentSecret: ByteVector32 = ByteVector32.Zeroes, targetExpiry: CltvExpiry = CltvExpiry(0),
                        assistedEdges: Set[GraphEdge] = Set.empty)

object ChannelMaster {
  type PartIdToAmount = Map[ByteVector, MilliSatoshi]
  val EXPECTING_PAYMENTS = "state-expecting-payments"
  val WAITING_FOR_ROUTE = "state-waiting-for-route"

  val CMDClearFailHistory = "cmd-clear-fail-history"
  val CMDChanGotOnline = "cmd-chan-got-online"
  val CMDAskForRoute = "cmd-ask-for-route"

  def incorrectDetails(add: UpdateAddHtlc): IncorrectOrUnknownPaymentDetails =
    IncorrectOrUnknownPaymentDetails(add.amountMsat, LNParams.blockCount.get)

  def failFinalPayloadSpec(fail: FailureMessage, finalPayloadSpec: FinalPayloadSpec): CMD_FAIL_HTLC =
    failHtlc(finalPayloadSpec.packet, fail, finalPayloadSpec.add)

  def failHtlc(packet: DecryptedPacket, fail: FailureMessage, add: UpdateAddHtlc): CMD_FAIL_HTLC = {
    val localFailurePacket = FailurePacket.create(packet.sharedSecret, fail)
    CMD_FAIL_HTLC(Left(localFailurePacket), add)
  }
}

abstract class ChannelMaster(payBag: PaymentBag, val chanBag: ChannelBag, pf: PathFinder) extends ChannelListener { me =>
  private[this] val getPaymentInfoMemo = memoize(payBag.getPaymentInfo)
  pf.listeners += PaymentMaster

  val sockBrandingBridge: ConnectionListener
  val sockChannelBridge: ConnectionListener

  val connectionListeners: Set[ConnectionListener] = Set(sockBrandingBridge, sockChannelBridge)
  val channelListeners: Set[ChannelListener] = Set(me, PaymentMaster)
  var listeners: Set[ChannelMasterListener] = Set.empty

  var all: List[Channel] = chanBag.all.map {
    case hasNormalCommits: HasNormalCommitments => ChannelNormal.make(channelListeners, hasNormalCommits, LNParams.chainWallet, chanBag)
    case hostedCommits: HostedCommits => ChannelHosted.make(channelListeners, hostedCommits, chanBag)
    case _ => throw new RuntimeException
  }

  val events: ChannelMasterListener = new ChannelMasterListener {
    override def outgoingFailed(data: PaymentSenderData): Unit = for (lst <- listeners) lst.outgoingFailed(data)
    override def outgoingSucceeded(data: PaymentSenderData): Unit = for (lst <- listeners) lst.outgoingSucceeded(data)
  }

  val incomingTimeoutWorker: ThrottledWork[ByteVector, Any] = new ThrottledWork[ByteVector, Any] {
    def process(hash: ByteVector, res: Any): Unit = Future(me stateUpdated Nil)(Channel.channelContext)
    def work(hash: ByteVector): Observable[Null] = Rx.ioQueue.delay(60.seconds)
    def error(canNotHappen: Throwable): Unit = none
  }

  // CHANNEL MANAGEMENT

  def inChannelOutgoingHtlcs: List[UpdateAddHtlc] = all.flatMap(Channel.chanAndCommitsOpt).flatMap(_.commits.allOutgoing)

  def fromNode(nodeId: PublicKey): List[ChanAndCommits] = all.flatMap(Channel.chanAndCommitsOpt).filter(_.commits.announce.na.nodeId == nodeId)

  def initConnect: Unit = all.flatMap(Channel.chanAndCommitsOpt).map(_.commits).foreach {
    case cs: HostedCommits => CommsTower.listen(connectionListeners, cs.announce.nodeSpecificPair, cs.announce.na, LNParams.hcInit)
    case cs: NormalCommits => CommsTower.listen(connectionListeners, cs.announce.nodeSpecificPair, cs.announce.na, LNParams.normInit)
    case otherwise => throw new RuntimeException
  }

  // RECEIVE/SEND UTILITIES

  def maxReceivableInfo: Option[CommitsAndMax] = {
    val canReceive = all.flatMap(Channel.chanAndCommitsOpt).filter(_.commits.updateOpt.isDefined).sortBy(_.commits.availableBalanceForReceive)
    // Example: (5, 50, 60, 100) -> (50, 60, 100), receivable = 50*3 = 150 (the idea is for smallest remaining channel to be able to handle an evenly split amount)
    val withoutSmall = canReceive.dropWhile(_.commits.availableBalanceForReceive * canReceive.size < canReceive.last.commits.availableBalanceForReceive).takeRight(4)
    val candidates = for (cs <- withoutSmall.indices map withoutSmall.drop) yield CommitsAndMax(cs, cs.head.commits.availableBalanceForReceive * cs.size)
    if (candidates.isEmpty) None else candidates.maxBy(_.maxReceivable).toSome
  }

  def checkIfSendable(paymentHash: ByteVector32, amount: MilliSatoshi): Int = {
    val presentInSenderFSM = PaymentMaster.data.payments.get(paymentHash)
    val presentInDb = payBag.getPaymentInfo(paymentHash)

    (presentInSenderFSM, presentInDb) match {
      case (_, info) if info.exists(_.isIncoming) => PaymentInfo.NOT_SENDABLE_INCOMING // We have an incoming payment with such payment hash
      case (_, info) if info.exists(SUCCEEDED == _.status) => PaymentInfo.NOT_SENDABLE_SUCCESS // This payment has been fulfilled a long time ago
      case (Some(senderFSM), _) if SUCCEEDED == senderFSM.state => PaymentInfo.NOT_SENDABLE_SUCCESS // This payment has just been fulfilled at runtime
      case (Some(senderFSM), _) if PENDING == senderFSM.state || INIT == senderFSM.state => PaymentInfo.NOT_SENDABLE_IN_FLIGHT // This payment is pending in FSM
      case _ if inChannelOutgoingHtlcs.exists(_.paymentHash == paymentHash) => PaymentInfo.NOT_SENDABLE_IN_FLIGHT // This payment is pending in channels
      case _ if PaymentMaster.totalSendable < amount => PaymentInfo.NOT_SENDABLE_LOW_BALANCE // Not enough money
      case _ => PaymentInfo.SENDABLE
    }
  }

  /**
    * Example: we subsequently get 3 incoming shards into 3 different channels
    * 1. on shard #1 and #2 `stateUpdated` is called, both shards are fine, we wait for the rest
    * 2. while waiting for the rest of shards, channel #1 becomes OFFLINE and channel #2 becomes SUSPENDED
    * 3. shard #3 arrives and `stateUpdated` is called, total sum is reached so we send 3 CMD_FULFILL_HTLC commands
    * 4. channel #1 (SLEEPING) stores a preimage, channel #2 (SUSPENDED) stores and sends out a preimage, channel #3 (OPEN) stores, sends out a preimage and then updates a state
    * 5. at this point sender sees payment as sent because preimage is delivered through channel #2 (SUSPENDED) and channel #3 (OPEN), we see payment as "pending with preimage revealed"
    * 6. we get channel #2 to OPEN by contacting host, new channel state has our fulfilled shard counted in, we still see payment as "pending with preimage revealed" because of channel #1 (SLEEPING)
    * 7. we close a wallet, then re-open it in an hour and receive another incoming payment into channel #2, pending shard in channel #1 (SLEEPING) is disregarded when `stateUpdated` is called
    * 8. channel #1 becomes OPEN, sends out a preimage and gets CMD_SIGN from `stateUpdated`, sends it out and updates a state, we get `incomingSucceeded` event and see payment as done
    */

  /**
    * Example: we subsequently get 2 incoming shards into 2 different channels
    * 1. on shard #1 and #2 `stateUpdated` is called, both shards are fine, we send 2 CMD_FULFILL_HTLC commands
    * 2. channel #1 stores, sends out a preimage and then updates a state, channel #2 is very busy right now so preimage is neither stored, nor sent
    * 3. we close a wallet, at this point sender sees payment as sent because preimage is delivered through channel #2, we see payment as just pending and have a dangling shard in channel #1
    * 4. we re-open a wallet, channel #1 is still SLEEPING, channel #2 becomes OPEN, `stateUpdated` is called and sends CMD_FULFILL_HTLC to channel #1 becase it sees a fulfilled shard in channel #2
    * 5. channel #1 stores a preimage, then becomes OPEN, sends out a preimage and then updates a state, we get `incomingSucceeded` event and see payment as done
    */

  /**
    * Normal incoming flow, a payment with 2 shards
    * 1. we get shard #1 into channel #1, `stateUpdated` is called, shard is recognized as incoming and pending in listener
    * 2. we get shard #2 into channel #2, `stateUpdated` is called, payment as a whole is recognized as fulfillable, 2 CMD_FULFILL_HTLC are sent to channels, both shards are pending in listener
    * 3. channel #2 stores, sends out a preimage and updates a state, `stateUpdated` is called, because of shard #1 payment is not recognized as fulfilled yet, shard #1 is pending in listener
    * 4. channel #1 stores, sends out a preimage and updates a state, `stateUpdated` is called, since shard #1 was the last one a payment as a whole is fulfilled in listener
    */

  override def stateUpdated(rejects: Seq[RemoteReject] = Nil): Unit = {
//    val allChansAndCommits: List[ChanAndCommits] = all.flatMap(Channel.chanAndCommitsOpt)
//    val allRevealedHashes: Set[ByteVector32] = allChansAndCommits.flatMap(_.commits.revealedHashes).toSet // Payment hashes with preimage is revealed, but state not updated yet
//    val allFulfilledAdds = toMapBy[ByteVector32, OurFulfilled](allChansAndCommits.flatMap(_.commits.localSpec.localFulfilled), _.paymentHash) // Successfully settled incoming shards
//    val allIncomingAdds = toMapBy[ByteVector32, AddResolution](allChansAndCommits.flatMap(_.commits.localSpec.incomingAdds).map(_.resolution), _.add.paymentHash) // Pending incoming shards
//
//    val allIncomingResolves: List[AddResolution] = allChansAndCommits.flatMap(_.commits.unansweredIncoming).map(_.resolution)
//    val badRightAway: List[BadAddResolution] = allIncomingResolves collect { case badAddResolution: BadAddResolution => badAddResolution }
//    val maybeGood: List[FinalPayloadSpec] = allIncomingResolves collect { case finalPayloadSpec: FinalPayloadSpec => finalPayloadSpec }
//
//    // Grouping by payment hash assumes we never ask for two different payments with the same hash!
//    val results = maybeGood.groupBy(_.add.paymentHash).map(_.swap).mapValues(getPaymentInfoMemo) map {
//      // TODO: reject incoming right away if we already have an upstream onion with same session key or downstream onion with same next key
//      // No such payment in our database or this is an outgoing payment, in any case we better fail it right away
//      case (payments, info) if !info.exists(_.isIncoming) => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
//      // These are multipart payments where preimage is revealed or some shards are partially fulfilled, proceed with fulfilling of the rest of shards
//      case (payments, Some(info)) if allFulfilledAdds.contains(info.paymentHash) => for (pay <- payments) yield CMD_FULFILL_HTLC(info.preimage, pay.add)
//      case (payments, Some(info)) if allRevealedHashes.contains(info.paymentHash) => for (pay <- payments) yield CMD_FULFILL_HTLC(info.preimage, pay.add)
//      // This is a multipart payment where some shards have different total amount values, this is a spec violation so we proceed with failing right away
//      case (payments, _) if payments.map(_.payload.totalAmount).toSet.size > 1 => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
//      // This is a payment where total amount is set to a value which is less than what we have originally requested, this is a spec violation so we proceed with failing right away
//      case (payments, Some(info)) if info.pr.amount.exists(_ > payments.head.payload.totalAmount) => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
//      // This is a payment where one of shards has a paymentSecret which is different from the one we have provided in invoice, this is a spec violation so we proceed with failing right away
//      case (payments, Some(info)) if !payments.flatMap(_.payload.paymentSecret).forall(info.pr.paymentSecret.contains) => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
//      // This is a payment which arrives too late, we would have too few blocks to prove that we have fulfilled it with an uncooperative peer, not a spec violation but we still fail it to be on safe side
//      case (payments, _) if payments.exists(_.add.cltvExpiry.toLong < LNParams.blockCount.get + LNParams.cltvRejectThreshold) => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
//      case (payments, Some(info)) if payments.map(_.add.amountMsat).sum >= payments.head.payload.totalAmount => for (pay <- payments) yield CMD_FULFILL_HTLC(info.preimage, pay.add)
//      // This can happen either when incoming payments time out or when we restart and have partial unanswered incoming leftovers, fail all of them
//      case (payments, _) if incomingTimeoutWorker.finishedOrNeverStarted => for (pay <- payments) yield failFinalPayloadSpec(PaymentTimeout, pay)
//      case _ => Nil
//    }
//
//    // This method should always be executed in channel context
//    // Using doProcess makes sure no external message gets intertwined in resolution
//    // For simplicity all resolutions are sent to all channels, filtering is up to channel
//    for (resolution <- badRightAway ++ results.flatten) all.foreach(_ doProcess resolution)
//    // Again, channel must only react to this if it has relevant changes
//    all.foreach(_ doProcess CMD_SIGN)
  }

  override def addReceived(add: UpdateAddHtlc): Unit = incomingTimeoutWorker replaceWork add.paymentHash

  override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, _, SLEEPING, OPEN | SUSPENDED) => me stateUpdated Nil }

  // SENDING OUTGOING PAYMENTS

  case class PaymentMasterData(payments: Map[ByteVector32, PaymentSender],
                               chanFailedAtAmount: Map[ChannelDesc, MilliSatoshi] = Map.empty withDefaultValue Long.MaxValue.msat,
                               nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty withDefaultValue 0,
                               chanFailedTimes: Map[ChannelDesc, Int] = Map.empty withDefaultValue 0) {

    def withFailureTimesReduced: PaymentMasterData = {
      val chanFailedTimes1 = chanFailedTimes.mapValues(_ / 2)
      val nodeFailedWithUnknownUpdateTimes1 = nodeFailedWithUnknownUpdateTimes.mapValues(_ / 2)
      // Cut in half recorded failure times to give failing nodes and channels a second chance and keep them susceptible to exclusion if they keep failing
      copy(chanFailedTimes = chanFailedTimes1, nodeFailedWithUnknownUpdateTimes = nodeFailedWithUnknownUpdateTimes1, chanFailedAtAmount = Map.empty)
    }
  }

  object PaymentMaster extends StateMachine[PaymentMasterData] with CanBeRepliedTo with ChannelListener { self =>
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
    def process(changeMessage: Any): Unit = scala.concurrent.Future(self doProcess changeMessage)
    become(PaymentMasterData(Map.empty), EXPECTING_PAYMENTS)

    def doProcess(change: Any): Unit = (change, state) match {
      case (CMDClearFailHistory, _) if data.payments.values.forall(fsm => SUCCEEDED == fsm.state || ABORTED == fsm.state) =>
        // This should be sent BEFORE sending another payment IF there are no active payments currently
        become(data.withFailureTimesReduced, state)

      case (cmd: CMD_SEND_MPP, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        for (assistedEdge <- cmd.assistedEdges) pf process assistedEdge
        relayOrCreateSender(cmd.paymentHash, cmd)
        self process CMDAskForRoute

      case (CMDChanGotOnline, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        // Payments may still have awaiting parts due to offline channels
        data.payments.values.foreach(_ doProcess CMDChanGotOnline)
        self process CMDAskForRoute

      case (CMDAskForRoute | PathFinder.NotifyOperational, EXPECTING_PAYMENTS) =>
        // This is a proxy to always send command in payment master thread
        // IMPLICIT GUARD: this message is ignored in all other states
        data.payments.values.foreach(_ doProcess CMDAskForRoute)

      case (req: RouteRequest, EXPECTING_PAYMENTS) =>
        // IMPLICIT GUARD: ignore in other states, payment will be able to re-send later
        val currentUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = getUsedCapacities
        val currentUsedDescs = mapKeys[DescAndCapacity, MilliSatoshi, ChannelDesc](currentUsedCapacities, _.desc, defVal = 0L.msat)
        val ignoreChansFailedTimes = data.chanFailedTimes collect { case (desc, failTimes) if failTimes >= pf.routerConf.maxChannelFailures => desc }
        val ignoreChansCanNotHandle = currentUsedCapacities collect { case (DescAndCapacity(desc, capacity), used) if used + req.amount >= capacity - req.amount / 24 => desc }
        val ignoreChansFailedAtAmount = data.chanFailedAtAmount collect { case (desc, failedAt) if failedAt - currentUsedDescs(desc) - req.amount / 8 <= req.amount => desc }
        val ignoreNodes = data.nodeFailedWithUnknownUpdateTimes collect { case (nodeId, failTimes) if failTimes >= pf.routerConf.maxStrangeNodeFailures => nodeId }
        val ignoreChans = ignoreChansFailedTimes.toSet ++ ignoreChansCanNotHandle ++ ignoreChansFailedAtAmount
        val request1 = req.copy(ignoreNodes = ignoreNodes.toSet, ignoreChannels = ignoreChans)
        pf process Tuple2(self, request1)
        become(data, WAITING_FOR_ROUTE)

      case (PathFinder.NotifyRejected, WAITING_FOR_ROUTE) =>
        // Pathfinder is not yet ready, switch local state back
        // pathfinder is expected to notify us once it gets ready
        become(data, EXPECTING_PAYMENTS)

      case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        data.payments.get(response.paymentHash).foreach(_ doProcess response)
        // Switch state to allow new route requests to come through
        become(data, EXPECTING_PAYMENTS)
        self process CMDAskForRoute

      case (ChannelFailed(descAndCapacity, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        // At this point an affected InFlight status IS STILL PRESENT so failedAtAmount = sum(inFlight)
        val newChanFailedAtAmount = data.chanFailedAtAmount(descAndCapacity.desc) min getUsedCapacities(descAndCapacity)
        val atTimes1 = data.chanFailedTimes.updated(descAndCapacity.desc, data.chanFailedTimes(descAndCapacity.desc) + increment)
        val atAmount1 = data.chanFailedAtAmount.updated(descAndCapacity.desc, newChanFailedAtAmount)
        become(data.copy(chanFailedAtAmount = atAmount1, chanFailedTimes = atTimes1), state)

      case (NodeFailed(nodeId, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        val newNodeFailedTimes = data.nodeFailedWithUnknownUpdateTimes(nodeId) + increment
        val atTimes1 = data.nodeFailedWithUnknownUpdateTimes.updated(nodeId, newNodeFailedTimes)
        become(data.copy(nodeFailedWithUnknownUpdateTimes = atTimes1), state)

      case (exception @ CMDException(_, cmd: CMD_ADD_HTLC), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        data.payments.get(cmd.paymentHash).foreach(_ doProcess exception)
        self process CMDAskForRoute

      case (fulfill: UpdateFulfillHtlc, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        relayOrCreateSender(fulfill.paymentHash, fulfill)
        self process CMDAskForRoute

      case (reject: RemoteReject, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        relayOrCreateSender(reject.ourAdd.paymentHash, reject)
        self process CMDAskForRoute

      case _ =>
    }

    /**
      * When in-flight outgoing HTLC gets trapped in a SUSPENDED channel: nothing happens (this is OK).
      * Can trapped in-flight HTLC be removed from FSM and retried? No because `stateUpdated` can not be called in SUSPENDED channel (this is desired).
      * Can FSM continue retrying other shards with a trapped shard present? Yes, because trapped one is not removed from FSM, its amount won't be re-sent again.
      * Can a payment with a trapped shard be fulfilled with or without FSM present? Yes because host has to provide a preimage even in SUSPENDED state and has an incentive to do so.
      * Can a payment with a trapped shard ever be failed? No, until SUSPENDED channel is overridden a shard will stay in it so payment will be either fulfilled or stay pending indefinitely.
      */

    // ChannelListener implementation, call `process` to get out of channel context

    override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = self process fulfill

    override def onException: PartialFunction[Malfunction, Unit] = { case (_, exception: CMDException) => self process exception }

    override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, _, SLEEPING | SUSPENDED, OPEN) => self process CMDChanGotOnline }

    override def stateUpdated(rejects: Seq[RemoteReject] = Nil): Unit = rejects foreach process

    // Utils

    private def relayOrCreateSender(paymentHash: ByteVector32, msg: Any): Unit = data.payments.get(paymentHash) match {
      case None => withSender(new PaymentSender, paymentHash, msg) // Can happen after restart with leftoverts in channels
      case Some(sender) => sender doProcess msg // Normal case, sender FSM is present
    }

    private def withSender(sender: PaymentSender, paymentHash: ByteVector32, msg: Any): Unit = {
      val payments1 = data.payments.updated(paymentHash, sender)
      become(data.copy(payments = payments1), state)
      sender doProcess msg
    }

    def getUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = {
      // This gets supposedly used capacities of external channels in a routing graph
      // we need this to exclude channels which definitely can't route a given amount right now
      val accumulator = mutable.Map.empty[DescAndCapacity, MilliSatoshi] withDefaultValue 0L.msat
      val descsAndCaps = data.payments.values.flatMap(_.data.inFlights).flatMap(_.route.routedPerChannelHop)
      descsAndCaps.foreach { case (amount, chanHop) => accumulator(chanHop.edge.toDescAndCapacity) += amount }
      accumulator
    }

    def totalSendable: MilliSatoshi = getSendable(all filter isOperational).values.sum

    def currentSendable: mutable.Map[ChanAndCommits, MilliSatoshi] = getSendable(all filter isOperationalAndOpen)

    def currentSendableExcept(wait: WaitForRouteOrInFlight): mutable.Map[ChanAndCommits, MilliSatoshi] = getSendable(all filter isOperationalAndOpen diff wait.localFailedChans)

    // What can be sent through given channels with waiting parts taken into account
    private def getSendable(chans: List[Channel] = Nil): mutable.Map[ChanAndCommits, MilliSatoshi] = {
      val finals: mutable.Map[ChanAndCommits, MilliSatoshi] = mutable.Map.empty[ChanAndCommits, MilliSatoshi] withDefaultValue 0L.msat
      val waits: mutable.Map[ByteVector32, PartIdToAmount] = mutable.Map.empty[ByteVector32, PartIdToAmount] withDefaultValue Map.empty
      // Wait part may have no route yet (but we expect a route to arrive) or it may be sent to channel but not processed by channel yet
      def waitPartsNotYetInChannel(cnc: ChanAndCommits): PartIdToAmount = waits(cnc.commits.channelId) -- cnc.commits.allOutgoing.map(_.partId)
      data.payments.values.flatMap(_.data.parts.values).collect { case wait: WaitForRouteOrInFlight => waits(wait.cnc.commits.channelId) += wait.partId -> wait.amount }
      // Example 1: chan toLocal=100, 10 in-flight AND IS present in channel already, resulting sendable = 90 (toLocal with in-flight) - 0 (in-flight - partId) = 90
      // Example 2: chan toLocal=100, 10 in-flight AND IS NOT preset in channel yet, resulting sendable = 100 (toLocal) - 10 (in-flight - nothing) = 90
      chans.flatMap(Channel.chanAndCommitsOpt).foreach(cnc => finals(cnc) = feeFreeBalance(cnc) - waitPartsNotYetInChannel(cnc).values.sum)
      finals.filter { case (cnc, sendable) => sendable >= cnc.commits.minSendable }
    }

    def feeFreeBalance(cnc: ChanAndCommits): MilliSatoshi = {
      // This adds maximum off-chain fee on top of next on-chain fee when another HTLC is added
      val spaceLeft = cnc.commits.maxInFlight - cnc.commits.allOutgoing.foldLeft(0L.msat)(_ + _.amountMsat)
      val withoutBaseFee = cnc.commits.availableBalanceForSend - LNParams.routerConf.searchMaxFeeBase
      val withoutAllFees = withoutBaseFee - withoutBaseFee * LNParams.routerConf.searchMaxFeePct
      spaceLeft.min(withoutAllFees)
    }
  }

  class PaymentSender extends StateMachine[PaymentSenderData] { self =>
    become(PaymentSenderData(CMD_SEND_MPP(ByteVector32.Zeroes, invalidPubKey), Map.empty), INIT)

    def doProcess(msg: Any): Unit = (msg, state) match {
      case (cmd: CMD_SEND_MPP, INIT | ABORTED) => assignToChans(PaymentMaster.currentSendable, PaymentSenderData(cmd, Map.empty), cmd.totalAmount)
      case (CMDException(_, cmd: CMD_ADD_HTLC), ABORTED) => self abortAndNotify data.withoutPartId(cmd.partId)
      case (reject: RemoteReject, ABORTED) => self abortAndNotify data.withoutPartId(reject.ourAdd.partId)

      case (reject: RemoteReject, INIT) =>
        val data1 = data.modify(_.cmd.paymentHash).setTo(reject.ourAdd.paymentHash)
        self abortAndNotify data1.withLocalFailure(NOT_RETRYING_NO_DETAILS, reject.ourAdd.amountMsat)

      case (fulfill: UpdateFulfillHtlc, INIT) =>
        // An idempotent transition, fires a success event with implanted hash
        val data1 = data.modify(_.cmd.paymentHash).setTo(fulfill.paymentHash)
        events.outgoingSucceeded(data1) // TODO: fire when all parts are done?
        become(data1, SUCCEEDED)

      case (_: UpdateFulfillHtlc, PENDING | ABORTED) =>
        // An idempotent transition, fires a success event
        events.outgoingSucceeded(data)
        become(data, SUCCEEDED)

      case (CMDChanGotOnline, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForBetterConditions =>
          assignToChans(PaymentMaster.currentSendable, data.withoutPartId(wait.partId), wait.amount)
        }

      case (CMDAskForRoute, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty =>
          val fakeLocalEdge = Tools.mkFakeLocalEdge(from = LNParams.format.keys.ourNodePubKey, toPeer = wait.cnc.commits.announce.na.nodeId)
          val params = RouteParams(pf.routerConf.searchMaxFeeBase, pf.routerConf.searchMaxFeePct, pf.routerConf.firstPassMaxRouteLength, pf.routerConf.firstPassMaxCltv)
          PaymentMaster process RouteRequest(data.cmd.paymentHash, partId = wait.partId, LNParams.format.keys.ourNodePubKey, data.cmd.targetNodeId, wait.amount, fakeLocalEdge, params)
        }

      case (fail: NoRouteAvailable, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty && wait.partId == fail.partId =>
          PaymentMaster currentSendableExcept wait collectFirst { case (cnc, chanSendable) if chanSendable >= wait.amount => cnc } match {
            case Some(anotherCapableCnc) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableCnc).tuple), PENDING)
            case None if canBeSplit(wait.amount) => become(data.withoutPartId(wait.partId), PENDING) doProcess SplitIntoHalves(wait.amount)
            case None => self abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(RUN_OUT_OF_RETRY_ATTEMPTS, wait.amount)
          }
        }

      case (found: RouteFound, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty && wait.partId == found.partId =>
          val finalPayload = Onion.createMultiPartPayload(wait.amount, data.cmd.totalAmount, data.cmd.targetExpiry, data.cmd.paymentSecret)
          val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(Sphinx.PaymentPacket)(wait.onionPrivKey, data.cmd.paymentHash, found.route.hops, finalPayload)
          val cmdAdd = CMD_ADD_HTLC(firstAmount, data.cmd.paymentHash, firstExpiry, PacketAndSecrets(onion.packet, onion.sharedSecrets), finalPayload)
          become(data.copy(parts = data.parts + wait.copy(flight = InFlightInfo(cmdAdd, found.route).toSome).tuple), PENDING)
          wait.cnc.chan process cmdAdd
        }

      case (CMDException(reason, cmd: CMD_ADD_HTLC), PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == cmd.partId =>
          val anotherCncOpt = PaymentMaster currentSendableExcept wait collectFirst { case (cnc, chanSendable) if chanSendable >= wait.amount => cnc }

          (anotherCncOpt, reason) match {
            case (Some(anotherCapableCnc), _) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableCnc).tuple), PENDING)
            case (None, _: ChannelUnavailable) => assignToChans(PaymentMaster.currentSendable, data.withoutPartId(wait.partId), wait.amount)
            case (None, _) => self abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(RUN_OUT_OF_RETRY_ATTEMPTS, wait.amount)
          }
        }

      case (reject: RemoteUpdateMalform, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == reject.ourAdd.partId =>
          PaymentMaster currentSendableExcept wait collectFirst { case (otherCnc, chanSendable) if chanSendable >= wait.amount => otherCnc } match {
            case Some(anotherCapableCnc) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableCnc).tuple), PENDING)
            case None => self abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(PEER_COULD_NOT_PARSE_ONION, wait.amount)
          }
        }

      case (reject: RemoteUpdateFail, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == reject.ourAdd.partId =>
          Sphinx.FailurePacket.decrypt(reject.fail.reason, wait.flight.get.cmd.packetAndSecrets.sharedSecrets) map {
            case pkt if pkt.originNode == data.cmd.targetNodeId || PaymentTimeout == pkt.failureMessage =>
              val data1 = data.withoutPartId(wait.partId).withRemoteFailure(wait.flight.get.route, pkt)
              self abortAndNotify data1

            case pkt @ Sphinx.DecryptedFailurePacket(originNodeId, failure: Update) =>
              // Pathfinder channels must be fully loaded from db at this point since we have already used them to construct a route
              val originalNodeIdOpt = pf.data.channels.get(failure.update.shortChannelId).map(_.ann getNodeIdSameSideAs failure.update)
              val isSignatureFine = originalNodeIdOpt.contains(originNodeId) && Announcements.checkSig(failure.update)(originNodeId)

              if (isSignatureFine) {
                pf process failure.update
                wait.flight.get.route.getEdgeForNode(originNodeId) match {
                  case Some(edge) if edge.updExt.update.shortChannelId != failure.update.shortChannelId =>
                    // This is fine: remote node has used a different channel than the one we have initially requested
                    // But remote node may send such errors infinitely so increment this specific type of failure
                    // This most likely means an originally requested channel has also been tried and failed
                    PaymentMaster doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)
                    PaymentMaster doProcess NodeFailed(originNodeId, increment = 1)

                  case Some(edge) if edge.updExt.update.core == failure.update.core =>
                    // Remote node returned the same update we used, channel is most likely imbalanced
                    // Note: we may have it disabled and new update comes enabled: still same update
                    PaymentMaster doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)

                  case _ =>
                    // Something like higher feerates or CLTV, channel is updated in graph and may be chosen once again
                    // But remote node may send oscillating updates infinitely so increment this specific type of failure
                    PaymentMaster doProcess NodeFailed(originNodeId, increment = 1)
                }
              } else {
                // Invalid sig is a severe violation, ban sender node for 6 subsequent payments
                PaymentMaster doProcess NodeFailed(originNodeId, pf.routerConf.maxStrangeNodeFailures * 32)
              }

              // Record a remote error and keep trying the rest of routes
              resolveRemoteFail(data.withRemoteFailure(wait.flight.get.route, pkt), wait)

            case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _: Node) =>
              // Node may become fine on next payment, but ban it for current attempts
              PaymentMaster doProcess NodeFailed(nodeId, pf.routerConf.maxStrangeNodeFailures)
              resolveRemoteFail(data.withRemoteFailure(wait.flight.get.route, pkt), wait)

            case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _) =>
              wait.flight.get.route.getEdgeForNode(nodeId).map(_.toDescAndCapacity) match {
                case Some(dnc) => PaymentMaster doProcess ChannelFailed(dnc, pf.routerConf.maxChannelFailures * 2) // Generic channel failure, ignore for rest of attempts
                case None => PaymentMaster doProcess NodeFailed(nodeId, pf.routerConf.maxStrangeNodeFailures) // Trampoline node failure, will be better addressed later
              }

              // Record a remote error and keep trying the rest of routes
              resolveRemoteFail(data.withRemoteFailure(wait.flight.get.route, pkt), wait)

          } getOrElse {
            val failure = UnreadableRemoteFailure(wait.flight.get.route)
            // Select nodes between our peer and final payee, they are least likely to send garbage
            val nodesInBetween = wait.flight.get.route.hops.map(_.nextNodeId).drop(1).dropRight(1)

            if (nodesInBetween.isEmpty) {
              // Garbage is sent by our peer or final payee, fail a payment
              val data1 = data.copy(failures = failure +: data.failures)
              self abortAndNotify data1.withoutPartId(wait.partId)
            } else {
              // We don't know which exact remote node is sending garbage, exclude a random one for current attempts
              PaymentMaster doProcess NodeFailed(shuffle(nodesInBetween).head, pf.routerConf.maxStrangeNodeFailures)
              resolveRemoteFail(data.copy(failures = failure +: data.failures), wait)
            }
          }
        }

      case (split: SplitIntoHalves, PENDING) =>
        val partOne: MilliSatoshi = split.amount / 2
        val partTwo: MilliSatoshi = split.amount - partOne
        // Must be run sequentially as these methods mutate data
        // as a result, both `currentSendable` and `data` are updated
        assignToChans(PaymentMaster.currentSendable, data, partOne)
        assignToChans(PaymentMaster.currentSendable, data, partTwo)

      case _ =>
    }

    def canBeSplit(totalAmount: MilliSatoshi): Boolean = totalAmount / 2 > pf.routerConf.mppMinPartAmount

    private def assignToChans(sendable: mutable.Map[ChanAndCommits, MilliSatoshi], data1: PaymentSenderData, amount: MilliSatoshi): Unit = {
      val directChansFirst = shuffle(sendable.toSeq).sortBy { case (cnc, _) => if (cnc.commits.announce.na.nodeId == data1.cmd.targetNodeId) 0 else 1 }
      // This is a terminal method in a sense that it either successfully assigns a given amount to channels or turns a payment into failed state
      // this method always sets a new partId to assigned parts so old payment statuses in data must be cleared before calling it

      directChansFirst.foldLeft(Map.empty[ByteVector, PartStatus] -> amount) {
        case ((accumulator, leftover), (cnc, chanSendable)) if leftover > 0L.msat =>
          // If leftover becomes less than theoretical sendable minimum then we must bump it upwards
          // Example: channel leftover=500, minSendable=10, chanSendable=200 -> sending 200
          // Example: channel leftover=300, minSendable=10, chanSendable=400 -> sending 300
          // Example: channel leftover=6, minSendable=10, chanSendable=200 -> sending 10
          // Example: channel leftover=6, minSendable=10, chanSendable=8 -> skipping
          val finalAmount = leftover max cnc.commits.minSendable min chanSendable

          if (finalAmount >= cnc.commits.minSendable) {
            val wait = WaitForRouteOrInFlight(randomKey, finalAmount, cnc)
            (accumulator + wait.tuple, leftover - finalAmount)
          } else (accumulator, leftover)

        case (collected, _) =>
          // No more amount to assign
          // Propagate what's collected
          collected

      } match {
        case (parts, leftover) if leftover <= 0L.msat =>
          // A whole mount has been fully split across our local channels
          // leftover may be slightly negative due to min sendable corrections
          become(data1.copy(parts = data1.parts ++ parts), PENDING)

        case (_, rest) if PaymentMaster.totalSendable - PaymentMaster.currentSendable.values.sum >= rest =>
          // Amount has not been fully split, but it is still possible to split it once some channel becomes OPEN
          become(data1.copy(parts = data1.parts + WaitForBetterConditions(randomKey, amount).tuple), PENDING)

        case _ =>
          // A non-zero leftover is present with no more channels left
          // partId should already have been removed from data at this point
          self abortAndNotify data1.withLocalFailure(NOT_ENOUGH_CAPACITY, amount)
      }
    }

    // Turn "in-flight" into "waiting for route" and expect for subsequent `CMDAskForRoute`
    private def resolveRemoteFail(data1: PaymentSenderData, wait: WaitForRouteOrInFlight): Unit =
      shuffle(PaymentMaster.currentSendable.toSeq) collectFirst { case (otherCnc, chanSendable) if chanSendable >= wait.amount => otherCnc } match {
        case Some(cnc) if wait.remoteAttempts < pf.routerConf.maxRemoteAttempts => become(data1.copy(parts = data1.parts + wait.oneMoreRemoteAttempt(cnc).tuple), PENDING)
        case _ if canBeSplit(wait.amount) => become(data1.withoutPartId(wait.partId), PENDING) doProcess SplitIntoHalves(wait.amount)
        case _ => self abortAndNotify data1.withoutPartId(wait.partId).withLocalFailure(RUN_OUT_OF_RETRY_ATTEMPTS, wait.amount)
      }

    private def abortAndNotify(data1: PaymentSenderData): Unit = {
      val notInChannel = inChannelOutgoingHtlcs.forall(_.paymentHash != data1.cmd.paymentHash)
      if (notInChannel && data1.inFlights.isEmpty) events.outgoingFailed(data1)
      become(data1, ABORTED)
    }
  }
}

trait ChannelMasterListener {
  def outgoingFailed(paymentSenderData: PaymentSenderData): Unit = none
  def outgoingSucceeded(paymentSenderData: PaymentSenderData): Unit = none
}