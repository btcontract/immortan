package immortan

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import immortan.utils.GraphUtils._
import immortan.utils.PaymentUtils._
import immortan.utils.ChannelUtils._
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import immortan.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.payment.{IncomingPacket, PaymentRequest}
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteUpdateFail}
import immortan.fsm.{IncomingPaymentProcessor, OutgoingPaymentMaster, TrampolinePaymentRelayer, TrampolineRevealed, TrampolineStopping}
import fr.acinq.eclair.wire.{PaymentTimeout, TemporaryNodeFailure, TrampolineFeeInsufficient, TrampolineOn, UpdateAddHtlc, UpdateFailHtlc}
import fr.acinq.eclair.payment.IncomingPacket.FinalPacket
import org.scalatest.funsuite.AnyFunSuite


class PaymentTrampolineRoutingSpec extends AnyFunSuite {
  test("Correctly parse trampoline routed payments sent to our fake nodeId") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    val ourParams = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0D, logExponent = 0D, CltvExpiryDelta(72))
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(100000L.msat), randomBytes32, dP, "Invoice", CltvExpiryDelta(18)) // Final payee
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer)
    val outerPaymentSecret = randomBytes32

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), trampolineFees = 1000L.msat)
    val reasonableTrampoline1 = createResolution(pr, 11000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 90000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    assert(TrampolinePaymentRelayer.validateRelay(ourParams, List(reasonableTrampoline1, reasonableTrampoline2), LNParams.blockCount.get).isEmpty)
  }

  test("Successfully parse a trampoline-to-legacy payment on payee side") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    // s -> us -> a

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, aP, "Invoice", CltvExpiryDelta(18)) // Final payee is A which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, a, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 707000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(500L))

    val List(add1) = cm.allInChannelOutgoing.values.flatten.toList
    IncomingPacket.decrypt(add1, aP).right.get.isInstanceOf[FinalPacket]
  }

  test("Successfully parse a multipart native trampoline payment on payee side") {
    // In this case payer sends 400,000 sat through us while total requested amount is 700,000 sat
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    // s -> us -> a

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, aP, "Invoice", CltvExpiryDelta(18)) // Final payee is A which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, cm) = makeChannelMasterWithBasicGraph

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (_, trampolineExpiry, trampolineOnion) = createInnerNativeTrampoline(partAmount = 400000L.msat, pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, a, CltvExpiryDelta(720), feeReserve)
    val theirAdd = createTrampolineAdd(pr, outerPartAmount = 404000L.msat, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, trampolineAmountTotal = 404000L.msat, trampolineExpiry, trampolineOnion, outerPaymentSecret)
    val reasonableTrampoline1 = ChannelMaster.initResolve(UpdateAddHtlcExt(theirAdd = theirAdd, remoteInfo = remoteNodeInfo)).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(500L))

    val List(add1) = cm.allInChannelOutgoing.values.flatten.toList
    val finalPacket = IncomingPacket.decrypt(add1, aP).right.get.asInstanceOf[FinalPacket]
    assert(finalPacket.payload.paymentSecret.get == pr.paymentSecret.get) // Payment secret is internal, payee will be able to group trampolines from various sources together
    assert(finalPacket.payload.totalAmount == 700000L.msat) // Total amount was not seen by relaying trampoline node, but equal to requested by payee
    assert(finalPacket.payload.amount == add1.amountMsat)
  }

  test("Successfully route a multipart trampoline payment") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    //             / b \
    // s -> us -> a     d
    //             \ c /

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, dP, "Invoice", CltvExpiryDelta(18)) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, cm) = makeChannelMasterWithBasicGraph
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(100L))

    assert(fsm.state == IncomingPaymentProcessor.RECEIVING)

    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    synchronized(wait(500L))

    assert(fsm.state == IncomingPaymentProcessor.SENDING)
    val senderDataSnapshot = cm.opm.data.payments(reasonableTrampoline1.fullTag).data
    val fulfill = UpdateAddHtlc(null, 1, null, paymentHash, null, senderDataSnapshot.inFlightParts.head.cmd.packetAndSecrets.packet, null)
    synchronized(wait(100L))

    assert(senderDataSnapshot.cmd.actualTotal == pr.amount.get) // With trampoline-to-legacy we find out a final amount
    val ourMinimalFee = TrampolinePaymentRelayer.relayFee(reasonableTrampoline3.packet.innerPayload, LNParams.trampoline)
    assert(senderDataSnapshot.cmd.totalFeeReserve == feeReserve - ourMinimalFee) // At the very least we collect base trampoline fee

    // Sender FSM in turn notifies relay FSM, meanwhile we simulate multiple incoming messages
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    cm.opm process RemoteFulfill(preimage, fulfill)
    synchronized(wait(100L))
    // We are guaranteed to receiver InFlightPayments in a same thread after fulfill because FSM asks for it
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    synchronized(wait(100L))

    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    val history = cm.payBag.listRecentRelays.headTry(cm.payBag.toRelayedPreimageInfo).get
    assert(history.relayed == pr.amount.get)
    assert(history.earned == 6984L.msat)
  }

  test("Reject on incoming timeout") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, dP, "Invoice", CltvExpiryDelta(18)) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, cm) = makeChannelMasterWithBasicGraph
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    var replies = List.empty[Any]
    ChannelMaster.NO_CHANNEL = new StateMachine[ChannelData] with CanBeRepliedTo {
      def process(change: Any): Unit = doProcess(change)
      def doProcess(change: Any): Unit = replies ::= change
    }

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(100L))

    assert(fsm.state == IncomingPaymentProcessor.RECEIVING)

    fsm doProcess IncomingPaymentProcessor.CMDTimeout
    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)
    // FSM asks channel master to provide current HTLC data right away
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(100L))

    assert(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(PaymentTimeout))
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    synchronized(wait(100L))
    // Sender FSM has been removed
    assert(cm.opm.data.payments.isEmpty)
  }

  test("Reject on outgoing timeout") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, dP, "Invoice", CltvExpiryDelta(18)) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, cm) = makeChannelMasterWithBasicGraph
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    var replies = List.empty[Any]
    ChannelMaster.NO_CHANNEL = new StateMachine[ChannelData] with CanBeRepliedTo {
      def process(change: Any): Unit = doProcess(change)
      def doProcess(change: Any): Unit = replies ::= change
    }

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 707000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(500L))

    assert(fsm.state == IncomingPaymentProcessor.SENDING)
    // Channels are not open so outgoing payments are waiting for timeout
    cm.opm.data.payments(reasonableTrampoline1.fullTag) doProcess OutgoingPaymentMaster.CMDAbort
    synchronized(wait(100L))
    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)
    // FSM asks channel master to provide current HTLC data right away
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(100L))
    assert(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(TemporaryNodeFailure))
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    synchronized(wait(100L))
    // Sender FSM has been removed
    assert(cm.opm.data.payments.isEmpty)
  }

  test("Fail to relay with outgoing channel getting SUSPENDED") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, dP, "Invoice", CltvExpiryDelta(18)) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, cm) = makeChannelMasterWithBasicGraph
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    var replies = List.empty[Any]
    ChannelMaster.NO_CHANNEL = new StateMachine[ChannelData] with CanBeRepliedTo {
      def process(change: Any): Unit = doProcess(change)
      def doProcess(change: Any): Unit = replies ::= change
    }

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    // Our only outgoing channel got SUSPENDED while we were collecting payment parts
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.SUSPENDED))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 707000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(500L))

    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)
    // FSM asks channel master to provide current HTLC data right away
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(100L))
    assert(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(TemporaryNodeFailure))
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    synchronized(wait(100L))
    // Sender FSM has been removed
    assert(cm.opm.data.payments.isEmpty)
  }

  test("Fail to relay with no route found") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, eP, "Invoice", CltvExpiryDelta(18)) // Final payee is E which is not in a graph!
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (_, _, cm) = makeChannelMasterWithBasicGraph
    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    var replies = List.empty[Any]
    ChannelMaster.NO_CHANNEL = new StateMachine[ChannelData] with CanBeRepliedTo {
      def process(change: Any): Unit = doProcess(change)
      def doProcess(change: Any): Unit = replies ::= change
    }

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, e, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 707000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(500L))

    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)
    // FSM asks channel master to provide current HTLC data right away
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: Nil)
    synchronized(wait(100L))
    assert(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(TrampolineFeeInsufficient))
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    synchronized(wait(100L))
    // Sender FSM has been removed
    assert(cm.opm.data.payments.isEmpty)
  }

  test("Restart after first fail, wind down on second fail") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, dP, "Invoice", CltvExpiryDelta(18)) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (normalStore, _, cm) = makeChannelMaster
    fillDirectGraph(normalStore)

    // s -> us -> a == d

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    synchronized(wait(500L))

    // Simulate making new FSM on restart
    fsm.become(null, IncomingPaymentProcessor.RECEIVING)
    val List(out1, out2) = cm.allInChannelOutgoing(reasonableTrampoline1.fullTag).toList
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    assert(fsm.data.asInstanceOf[TrampolineStopping].retryOnceFinalized)

    cm.all = Map.empty // Make it appear as if all outoing leftovers have been cleared (needed for sender FSM to fire an event)
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out1.channelId, out1.id, randomBytes32.bytes), out1)
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out2.channelId, out2.id, randomBytes32.bytes), out2)
    synchronized(wait(200L))

    // All outgoing parts have been cleared, but we still have incoming parts and maybe can try again (unless CLTV delta has expired)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    assert(fsm.state == IncomingPaymentProcessor.SENDING)
    synchronized(wait(200L))
    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
  }

  test("Wind down after pathologc fail") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, dP, "Invoice", CltvExpiryDelta(18)) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (normalStore, _, cm) = makeChannelMaster
    fillDirectGraph(normalStore)

    // s -> us -> a == d

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    synchronized(wait(500L))

    // Simulate making new FSM on restart
    fsm.become(null, IncomingPaymentProcessor.RECEIVING)
    val List(out1, out2) = cm.allInChannelOutgoing(reasonableTrampoline1.fullTag).toList
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = reasonableTrampoline2 :: Nil)
    // Pathologic state: we do not have enough incoming payments, yet have outgoing payments (user removed an HC?)
    assert(!fsm.data.asInstanceOf[TrampolineStopping].retryOnceFinalized)

    cm.all = Map.empty // Make it appear as if all outoing leftovers have been cleared (needed for sender FSM to fire an event)
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out1.channelId, out1.id, randomBytes32.bytes), out1)
    cm.opm process RemoteUpdateFail(UpdateFailHtlc(out2.channelId, out2.id, randomBytes32.bytes), out2)
    synchronized(wait(200L))

    var replies = List.empty[Any]
    ChannelMaster.NO_CHANNEL = new StateMachine[ChannelData] with CanBeRepliedTo {
      def process(change: Any): Unit = doProcess(change)
      def doProcess(change: Any): Unit = replies ::= change
    }

    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline2 :: Nil)
    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
    assert(replies.head.asInstanceOf[CMD_FAIL_HTLC].reason == Right(TemporaryNodeFailure))
  }

  test("Fulfill in a pathologic fail state") {
    LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray), seed = None)
    LNParams.trampoline = TrampolineOn(minimumMsat = 1000L.msat, maximumMsat = 10000000L.msat, feeBaseMsat = 10L.msat, feeProportionalMillionths = 100, exponent = 0.97D, logExponent = 3.9D, CltvExpiryDelta(72))
    LNParams.routerConf = routerConf // Replace with the one which allows for smaller parts

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val pr = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(700000L.msat), paymentHash, dP, "Invoice", CltvExpiryDelta(18)) // Final payee is D which we do not have direct channels with
    val remoteNodeInfo = RemoteNodeInfo(nodeId = s, address = null, alias = "peer-1") // How we see an initial sender (who is our peer with a private channel)
    val (normalStore, _, cm) = makeChannelMaster
    fillDirectGraph(normalStore)

    // s -> us -> a == d

    val outerPaymentSecret = randomBytes32
    val feeReserve = 7000L.msat

    // Private channel US -> A
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer-2")
    cm.chanBag.put(hcs1)
    cm.all = Channel.load(Set(cm), cm.chanBag)
    cm.all.values.foreach(chan => chan.BECOME(chan.data, Channel.OPEN))

    val (trampolineAmountTotal, trampolineExpiry, trampolineOnion) = createInnerLegacyTrampoline(pr, remoteNodeInfo.nodeId, remoteNodeInfo.nodeSpecificPubKey, d, CltvExpiryDelta(720), feeReserve)
    val reasonableTrampoline1 = createResolution(pr, 105000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline2 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]
    val reasonableTrampoline3 = createResolution(pr, 301000L.msat, remoteNodeInfo, trampolineAmountTotal, trampolineExpiry, trampolineOnion, outerPaymentSecret).asInstanceOf[ReasonableTrampoline]

    val fsm = new TrampolinePaymentRelayer(reasonableTrampoline1.fullTag, cm)
    fsm doProcess makeInFlightPayments(out = Nil, in = reasonableTrampoline1 :: reasonableTrampoline3 :: reasonableTrampoline2 :: Nil)
    synchronized(wait(500L))

    // Simulate making new FSM on restart
    fsm.become(null, IncomingPaymentProcessor.RECEIVING)
    val List(out1, out2) = cm.allInChannelOutgoing(reasonableTrampoline1.fullTag).toList
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = reasonableTrampoline2 :: Nil)
    // Pathologic state: we do not have enough incoming payments, yet have outgoing payments (user removed an HC?)
    assert(!fsm.data.asInstanceOf[TrampolineStopping].retryOnceFinalized)

    val senderDataSnapshot = cm.opm.data.payments(reasonableTrampoline1.fullTag).data
    val fulfill = UpdateAddHtlc(null, 1, null, paymentHash, null, senderDataSnapshot.inFlightParts.head.cmd.packetAndSecrets.packet, null)
    cm.opm process RemoteFulfill(preimage, fulfill)
    synchronized(wait(200L))
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = reasonableTrampoline2 :: Nil)
    assert(fsm.data.asInstanceOf[TrampolineRevealed].preimage == preimage)
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)

    fsm.become(null, IncomingPaymentProcessor.RECEIVING)
    assert(cm.payBag.getPreimage(paymentHash).toOption.contains(preimage))
    // All incoming have been fulfilled, but we still have outgoing (channel offline?)
    // This is fine since we have a preiamge in case of what, but FSM is kept working
    fsm doProcess makeInFlightPayments(out = out1 :: out2 :: Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.FINALIZING)
    fsm doProcess makeInFlightPayments(out = Nil, in = Nil)
    assert(fsm.state == IncomingPaymentProcessor.SHUTDOWN)
  }
}