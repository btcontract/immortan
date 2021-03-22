package immortan.fsm

import immortan.{ChannelListener, ChannelMaster, ChannelNormal, CommsTower, ConnectionListener, LNParams, RemoteNodeInfo, WalletExt}
import fr.acinq.eclair.channel.{ChannelVersion, DATA_WAIT_FOR_FUNDING_CONFIRMED, INPUT_INIT_FUNDEE, PersistentChannelData}
import fr.acinq.eclair.wire.{HasChannelId, HasTemporaryChannelId, Init, LightningMessage, OpenChannel}
import immortan.Channel.{WAIT_FOR_ACCEPT, WAIT_FUNDING_DONE}
import immortan.ChannelListener.{Malfunction, Transition}
import fr.acinq.eclair.io.Peer


// Important: this must be initiated when chain tip is actually known
abstract class NCFundeeOpenHandler(info: RemoteNodeInfo, theirOpen: OpenChannel, cm: ChannelMaster) {
  def onPeerDisconnect(worker: CommsTower.Worker): Unit
  def onEstablished(channel: ChannelNormal): Unit
  def onFailure(err: Throwable): Unit

  val freshChannel: ChannelNormal = new ChannelNormal(cm.chanBag) {
    def SEND(messages: LightningMessage*): Unit = CommsTower.sendMany(messages, info.nodeSpecificPair)
    def STORE(normalData: PersistentChannelData): PersistentChannelData = cm.chanBag.put(normalData)
    var chainWallet: WalletExt = LNParams.chainWallet
  }

  private val makeChanListener = new ConnectionListener with ChannelListener {
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val params = Peer.makeChannelParams(info, freshChannel.chainWallet.wallet, funder = false, theirOpen.fundingSatoshis, ChannelVersion.STATIC_REMOTEKEY)
      freshChannel process INPUT_INIT_FUNDEE(remoteInfo = info, localParams = params, remoteInit = theirInit, ChannelVersion.STATIC_REMOTEKEY, theirOpen)
    }

    override def onMessage(worker: CommsTower.Worker, message: LightningMessage): Unit = message match {
      case msg: HasTemporaryChannelId if msg.temporaryChannelId == theirOpen.temporaryChannelId => freshChannel process message
      case msg: HasChannelId if msg.channelId == theirOpen.temporaryChannelId => freshChannel process message
      case _ => // Do nothing to avoid conflicts
    }

    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      // Peer has disconnected during HC opening process
      onPeerDisconnect(worker)
      rmTempListener
    }

    override def onBecome: PartialFunction[Transition, Unit] = {
      case (_, _, data: DATA_WAIT_FOR_FUNDING_CONFIRMED, WAIT_FOR_ACCEPT, WAIT_FUNDING_DONE) =>
        // It is up to NC to store itself and communicate successful opening
        cm.implantChannel(data.commitments, freshChannel)
        onEstablished(freshChannel)
        rmTempListener
    }

    override def onException: PartialFunction[Malfunction, Unit] = {
      // Something went wrong while trying to establish a channel

      case (_, error) =>
        onFailure(error)
        rmTempListener
    }
  }

  freshChannel.listeners = Set(makeChanListener)
  CommsTower.listen(Set(makeChanListener, cm.sockBrandingBridge), info.nodeSpecificPair, info)
  def rmTempListener: Unit = CommsTower.listeners(info.nodeSpecificPair) -= makeChanListener
}