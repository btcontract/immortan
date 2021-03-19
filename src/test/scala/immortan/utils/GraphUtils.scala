package immortan.utils

import fr.acinq.eclair._
import immortan.crypto.Tools._
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64}
import fr.acinq.eclair.router.{Announcements, ChannelUpdateExt, Sync}
import fr.acinq.eclair.router.Router.{ChannelDesc, RouteParams, RouteRequest, RouterConf}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, FullPaymentTag, PaymentTagTlv}
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.bitcoin.Crypto.PublicKey
import immortan.sqlite.SQLiteNetwork
import scodec.bits.ByteVector


object GraphUtils {
  val PlaceHolderSig: ByteVector64 = ByteVector64(ByteVector.fill(64)(0xaa))

  val (a, b, c, d, s, e) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

  var routerConf: RouterConf =
    RouterConf(maxCltv = CltvExpiryDelta(2016), routeHopDistance = 6,
      mppMinPartAmount = MilliSatoshi(30000000L), maxRemoteAttempts = 12,
      maxChannelFailures = 12, maxStrangeNodeFailures = 12)

  val offChainFeeRatio = 0.01 // %

  def makeUpdate(shortChannelId: ShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey,
                 feeBase: MilliSatoshi, feeProportionalMillionth: Int, cltvDelta: CltvExpiryDelta,
                 minHtlc: MilliSatoshi, maxHtlc: MilliSatoshi): ChannelUpdate = {

    val isNode1 = Announcements.isNode1(nodeId1, nodeId2)
    ChannelUpdate(signature = PlaceHolderSig, chainHash = Block.RegtestGenesisBlock.hash, shortChannelId = shortChannelId,
      timestamp = System.currentTimeMillis, messageFlags = 1, channelFlags = if (isNode1) 0 else 1, cltvExpiryDelta = cltvDelta,
      htlcMinimumMsat = minHtlc, feeBaseMsat = feeBase, feeProportionalMillionths = feeProportionalMillionth,
      htlcMaximumMsat = maxHtlc.toSome)
  }

  def makeEdge(shortChannelId: ShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey, feeBase: MilliSatoshi, feeProportionalMillionth: Int,
               minHtlc: MilliSatoshi, maxHtlc: MilliSatoshi, cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0), score: Int = 1): GraphEdge = {

    val update = makeUpdate(shortChannelId, nodeId1, nodeId2, feeBase, feeProportionalMillionth, cltvDelta, minHtlc, maxHtlc)
    val updateExt = ChannelUpdateExt(update, Sync.getChecksum(update), score, useHeuristics = true)
    GraphEdge(ChannelDesc(shortChannelId, nodeId1, nodeId2), updateExt)
  }

  def makeAnnouncement(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey): ChannelAnnouncement = {

    val isNode1 = Announcements.isNode1(nodeIdA, nodeIdB)
    val (nodeId1, nodeId2) = if (isNode1) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)

    ChannelAnnouncement(PlaceHolderSig, PlaceHolderSig, PlaceHolderSig, PlaceHolderSig, Features.empty,
      Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), nodeId1, nodeId2,
      randomKey.publicKey, randomKey.publicKey)
  }

  def getParams(conf: RouterConf, amount: MilliSatoshi, feeRatio: Double): RouteParams = {
    RouteParams(feeReserve = amount * feeRatio, routeMaxLength = conf.routeHopDistance, routeMaxCltv = conf.maxCltv)
  }

  def makeRouteRequest(amount: MilliSatoshi, params: RouteParams, fromNode: PublicKey, fromLocalEdge: GraphEdge): RouteRequest = {
    val fullTag = FullPaymentTag(paymentHash = randomBytes32, paymentSecret = randomBytes32, tag = PaymentTagTlv.LOCALLY_SENT)
    RouteRequest(fullTag, partId = ByteVector32.Zeroes.bytes, fromNode, target = d, amount, fromLocalEdge, params)
  }

  def fillBasicGraph(store: SQLiteNetwork): Unit = {
    val channelAB: ChannelAnnouncement = makeAnnouncement(1L, a, b)
    val channelAC: ChannelAnnouncement = makeAnnouncement(2L, a, c)
    val channelBD: ChannelAnnouncement = makeAnnouncement(3L, b, d)
    val channelCD: ChannelAnnouncement = makeAnnouncement(4L, c, d)

    val updateABFromA: ChannelUpdate = makeUpdate(ShortChannelId(1L), a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val updateABFromB: ChannelUpdate = makeUpdate(ShortChannelId(1L), b, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    val updateACFromA: ChannelUpdate = makeUpdate(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(134), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val updateACFromC: ChannelUpdate = makeUpdate(ShortChannelId(2L), c, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(134), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    val updateBDFromB: ChannelUpdate = makeUpdate(ShortChannelId(3L), b, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val updateBDFromD: ChannelUpdate = makeUpdate(ShortChannelId(3L), d, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    val updateCDFromC: ChannelUpdate = makeUpdate(ShortChannelId(4L), c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)
    val updateCDFromD: ChannelUpdate = makeUpdate(ShortChannelId(4L), d, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), minHtlc = 10L.msat, maxHtlc = 500000.msat)

    store.db txWrap {
      store.addChannelAnnouncement(channelAB)
      store.addChannelAnnouncement(channelAC)
      store.addChannelAnnouncement(channelBD)
      store.addChannelAnnouncement(channelCD)

      store.addChannelUpdateByPosition(updateABFromA)
      store.addChannelUpdateByPosition(updateABFromB)

      store.addChannelUpdateByPosition(updateACFromA)
      store.addChannelUpdateByPosition(updateACFromC)

      store.addChannelUpdateByPosition(updateBDFromB)
      store.addChannelUpdateByPosition(updateBDFromD)

      store.addChannelUpdateByPosition(updateCDFromC)
      store.addChannelUpdateByPosition(updateCDFromD)
    }
  }
}