package com.vibes.actors

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.LazyLogging
import com.vibes.actions.{MasterActions, ReducerActions}
import com.vibes.models._
import com.vibes.utils.Joda._
import com.vibes.utils.VConf
import org.joda.time.Seconds.secondsBetween
import org.joda.time._

// Takes final RAW result of the simulator and converts it to a format processable by a client

/*
* Once the simulation is over, the Reducer takes a set of all nodes as input and finds
* the longest chain in the network (every Node holds a blockchain). The longest chain
* contains a list of blocks with arrival times and recipients. Each block contains a list
* of transaction with arrival times and recipients as well. Based on this information it is
* trivial to compute the final output of the simulator.
* The lists of recipient and arrival times are an implementation detail of VIBES that helps
* us backtrack events in the network, they do not necessarily correspond to any entity in a
* real blockchain network
*/
class ReducerActor(masterActor: ActorRef) extends Actor with LazyLogging {
  private var nodes: Set[VNode] = Set.empty
  var blocks: Set[VBlock] = Set.empty
  private var start             = DateTime.now
  private var transactionPool: Set[VTransaction] = Set.empty

  override def preStart(): Unit = {
    start = DateTime.now
    logger.debug(s"ReducerActor started ${self.path}")
  }

  override def receive: Receive = {
    case ReducerActions.ReceiveNode(node) =>
      assert(!nodes.contains(node))
      nodes += node

      if (VConf.numberOfNodes == nodes.size) {
        val events = ReducerActor.calculateResult(nodes, start, transactionPool, blocks)
        masterActor ! MasterActions.FinishEvents(events)
      }

    case ReducerActions.AddBlock(block) =>
      assert(!blocks.contains(block))
      blocks += block
  }
}

case class ReducerIntermediateResult(
  events: List[VEventType],
  duration: Double,
  longestChainLength: Int,
  longestChainSize: Int,
  longestChainNumberTransactions: Int,
  timesAvgWithOutliers: (Float, Float, Float),
  timesAvgNoOutliers: (Float, Float, Float),
  firstBlockNumberOfRecipients: Int,
  lastBlockNumberOfRecipients: Int,
  nonSegWitMaxTransactionsPerBlock: Int,
  segWitMaxTransactionsPerBlock: Int,
  nonSegWitMaxTPS: Double,
  segWitMaxTPS: Double,
  segWitMaxBlockWeight: Int,
  transactions: List[VTransaction],
  staleBlocks: Int,
  attackSucceeded: Int,
  successfulAttackInBlocks: Int,
  probabilityOfSuccessfulAttack: Double,
  maximumSafeTransactionValue: Int,
  maliciousBlockchainLength: Int,
  goodBlockchainLength: Int,
  attackDuration: Int,
  B: Double,
  o: Int,
  alpha: Int,
  k: Int,
  actualTPS: Double,
  avgBlockTime: Int,
  simulationStart: String,
  confirmedFloodAttackTransactions: Int,
  floodAttackSpentTransactionFees: Int,
  confirmedTransactionsBelowTargetTransactionFee: Int
)

object ReducerActor extends LazyLogging {
  def factorial(n:Double):Double = if(n==0) 1 else n * factorial(n-1)

  def props(masterActor: ActorRef): Props = Props(new ReducerActor(masterActor))

  def calculateResult(nodes: Set[VNode], start: DateTime, transactionPool: Set[VTransaction], blocks: Set[VBlock]): ReducerIntermediateResult = {
    var events: List[VEventType] = List.empty
    var longestChain             = nodes.last.blockchain
    var lastNode = nodes.last

    // find longestChain (aka longest distributed ledger)
    nodes.foreach { node =>
      if (node.blockchain.lengthCompare(longestChain.size) > 0) {
        longestChain = node.blockchain
        lastNode = node
      }
    }

    val size = longestChain.flatMap(_.transactions).size * VConf.transactionSize / 1000

    // remove all None nonsense
    val times: List[(Float, Float, Float)] = longestChain
      .map(block => block.calculatePropagationTimes())
      .filter(time => time._1.isDefined && time._2.isDefined && time._3.isDefined)
      .map(time => (time._1.getOrElse(0.toFloat), time._2.getOrElse(0.toFloat), time._3.getOrElse(0.toFloat)))

    val timesWithOutliers = times.foldLeft((0.0, 0.0, 0.0)) { (timesPrev, timesCurr: (Float, Float, Float)) =>
      (
        timesPrev._1 + timesCurr._1,
        timesPrev._2 + timesCurr._2,
        timesPrev._3 + timesCurr._3
      )
    }

    val timesNoOutliersFiltered = times.filter { time =>
      time._3.toInt < 180
    }

    val timesNoOutliers = timesNoOutliersFiltered
      .foldLeft((0.0, 0.0, 0.0)) { (timesPrev, timesCurr: (Float, Float, Float)) =>
        (
          timesPrev._1 + timesCurr._1,
          timesPrev._2 + timesCurr._2,
          timesPrev._3 + timesCurr._3,
        )
      }

    import java.text.DecimalFormat
    val formatter = new DecimalFormat("#,###") // this should be depending on the regional settings of the host pc. Point for Germany, comma for english speaking countries

    val timesSize = if (times.nonEmpty) times.size else 1
    val timesAvgWithOutliers = (formatter.format(timesWithOutliers._1 / timesSize).toFloat,
                                formatter.format(timesWithOutliers._2 / timesSize).toFloat,
                                formatter.format(timesWithOutliers._3 / timesSize).toFloat)

    val timesNoOutliersFilteredSize = if (timesNoOutliersFiltered.nonEmpty) timesNoOutliersFiltered.size else 1
    val timesAvgNoOutliers = (formatter.format(timesNoOutliers._1 / timesNoOutliersFilteredSize).toFloat,
                              formatter.format(timesNoOutliers._2 / timesNoOutliersFilteredSize).toFloat,
                              formatter.format(timesNoOutliers._3 / timesNoOutliersFilteredSize).toFloat)

    logger.debug("==============================================================")
    logger.debug("============================OUTPUT============================")
    logger.debug("==============================================================")

    val interval                       = new Interval(start, new Instant)
    val duration                       = interval.toDuration.toStandardSeconds.getSeconds.toDouble
    val longestChainLength             = longestChain.size
    val longestChainSize               = size
    val longestChainNumberTransactions = longestChain.flatMap(_.transactions).size

    logger.debug(s"SIMULATION TOOK... $duration SECONDS")
    logger.debug(s"LONGEST CHAIN... $longestChainLength BLOCKS")
    logger.debug(s"LONGEST CHAIN SIZE.... $longestChainSize KB")
    logger.debug(s"LONGEST CHAIN NUMBER OF TRANSACTIONS... $longestChainNumberTransactions")
    logger.debug(s"NUMBER OF BLOCKS FOR PROPAGATION TIME ${times.size}")
    logger.debug(s"BLOCK PROPAGATION TIME 10%... ${timesAvgWithOutliers._1} SECONDS")
    logger.debug(s"BLOCK PROPAGATION TIME 50%... ${timesAvgWithOutliers._2} SECONDS")
    logger.debug(s"BLOCK PROPAGATION TIME 90%... ${timesAvgWithOutliers._3} SECONDS")

    logger.debug(s"NUMBER OF BLOCKS FOR PROPAGATION TIME (NO OUTLIERS) ${timesNoOutliersFiltered.size}")
    logger.debug(s"BLOCK PROPAGATION TIME 10% (NO OUTLIERS)... ${timesAvgNoOutliers._1} SECONDS")
    logger.debug(s"BLOCK PROPAGATION TIME 50% (NO OUTLIERS)... ${timesAvgNoOutliers._2} SECONDS")
    logger.debug(s"BLOCK PROPAGATION TIME 90% (NO OUTLIERS)... ${timesAvgNoOutliers._3} SECONDS")

    // +1 cause we don't count origin
    val firstBlockNumberOfRecipients = longestChain.last.numberOfRecipients + 1
    logger.debug(s"FIRST BLOCK RECEIVED BY... $firstBlockNumberOfRecipients  OUT OF ${VConf.numberOfNodes} NODES")

    val lastBlockNumberOfRecipients = longestChain.head.numberOfRecipients + 1
    logger.debug(s"LAST BLOCK RECEIVED BY... $lastBlockNumberOfRecipients OUT OF ${VConf.numberOfNodes} NODES")

    logger.debug(s"TOTAL TRANSACTION POOL... ${longestChain.head.transactionPoolSize}")

    val simulationStart: String = VConf.simulationStart.toString()
    logger.debug(s"SIMULATION START... $simulationStart")

    // works only for constant transaction size and weight, otherwise an array is necessary
    var segWitMaxBlockWeight = 0 // nonSegWitMaxBlockSize = VConf.maxBlockSize
    var maxTransactionsPerBlock = 0
    var segWitMaxTPS: Double = 0
    var nonSegWitMaxTPS: Double = 0
    if (VConf.transactionSize != 0) {
      // multiplies by 1000 because maxBlockSize is in KB and transaction size is in B
      maxTransactionsPerBlock = VConf.nonSegWitMaxTransactionsPerBlock
      nonSegWitMaxTPS = VConf.nonSegWitMaxTransactionsPerBlock.toDouble / VConf.blockTime.toDouble
      nonSegWitMaxTPS = (math rint nonSegWitMaxTPS * 1000) / 1000
    }
    if (VConf.maxBlockWeight != 0 && VConf.transactionWeight != 0) {
      segWitMaxBlockWeight = VConf.segWitMaxTransactionsPerBlock * VConf.transactionSize
      maxTransactionsPerBlock = VConf.segWitMaxTransactionsPerBlock
      segWitMaxTPS = VConf.segWitMaxTransactionsPerBlock.toDouble / VConf.blockTime.toDouble
      segWitMaxTPS = (math rint segWitMaxTPS * 1000) / 1000
    }
    logger.debug(s"MAXIMAL POSSIBLE SEGWIT TRANSACTIONS PER BLOCK... ${VConf.segWitMaxTransactionsPerBlock}")
    logger.debug(s"MAXIMAL POSSIBLE SEGWIT BLOCK WEIGHT... $segWitMaxBlockWeight")
    logger.debug(s"MAXIMAL POSSIBLE NONSEGWIT TRANSACTIONS PER BLOCK... ${VConf.nonSegWitMaxTransactionsPerBlock}")

    // tps stuff
    logger.debug(s"MAXIMAL SEGWIT TPS... $segWitMaxTPS")
    logger.debug(s"MAXIMAL NONSEGWIT TPS... $nonSegWitMaxTPS")
    var actualTPS: Double = longestChainNumberTransactions.toDouble / secondsBetween(VConf.simulationStart, VConf.simulateUntil).getSeconds.toDouble
    actualTPS = (math rint actualTPS * 1000) / 1000
    logger.debug(s"AVERAGE TPS... $actualTPS")

    events = longestChain.flatMap { block =>
      var blockEvents: List[VEventType] = List.empty
      blockEvents ::= MinedBlock(block.origin, timestamp = block.timestamp, transactionPoolSize = block.transactionPoolSize, level = block.level, transactions = block.transactions, isMalicious = block.origin.isMalicious)
      blockEvents :::= block.currentRecipients.map { recipient =>
        TransferBlock(recipient.from, recipient.to, timestamp = recipient.timestamp)
      }
      blockEvents
    }

    // logger.debug(s"LAST NODE... ${lastNode.transactionPool.diff(longestChain.flatMap(_.transactions).toSet).size}")
    logger.debug(s"TRANSACTION SIZE OF LAST NODE TRANSACTION POOL... ${lastNode.transactionPool.size}")
    var transactions: List[VTransaction] = longestChain.flatMap(_.transactions) ++ lastNode.transactionPool.diff(longestChain.flatMap(_.transactions).toSet)
    logger.debug(s"TRANSACTION SIZE OF LONGEST CHAIN... ${longestChain.flatMap(_.transactions).size}")
    logger.debug(s"TRANSACTION SIZE... ${transactions.size}")
    transactions = transactions.sortBy((transaction: VTransaction) => transaction.transactionFee)

    val avgBlockTimeDouble: Double = secondsBetween(VConf.simulationStart, longestChain.head.timestamp).getSeconds.toDouble / longestChain.size
    val avgBlockTime: Int = (math rint avgBlockTimeDouble).toInt
    logger.debug(s"AVG BLOCK TIME... $avgBlockTime... SHOULD BE ${VConf.blockTime}")

    var successfulAttackInBlocks = 0
    var probabilityOfSuccessfulAttack : Double = 0
    var maximalSafeTransactionValue = 0
    var maliciousBlockchainLength = 0
    var goodBlockchainLength = 0
    var attackDuration = 0
    var B: Double = 0
    var o = 0
    var alpha = 0
    var k = 0

    var attackSucceeded = 0
    if (VConf.strategy == "BITCOIN_LIKE_BLOCKCHAIN" && VConf.isAlternativeHistoryAttack) {
      // formula from arXiv:1402.2009v1 [cs.CR] 9 Feb 2014
      // calculation of the success probability for an attack
      var r: Double = 0
      if (VConf.confirmations > 0 && VConf.hashRate < 50) {
        val q: Double = VConf.hashRate.toDouble / 100
        val p: Double = 1 - q
        val n = VConf.confirmations
        if (q < p) {
          var sum: Double = 0
          for (m <- 0 until n + 1) {
            sum += ((factorial(m + n - 1) / (factorial(m) * factorial( n - 1))) * ((Math.pow(p, n) * Math.pow(q, m)) - (Math.pow(p, m) * Math.pow(q, n))))
          }
          r = 1 - sum
          probabilityOfSuccessfulAttack = (math rint r * 100000) / 1000
        }
      } else {
        r = 1
        probabilityOfSuccessfulAttack = 100
      }
      logger.debug(s"ATTACK SUCCESS PROBABILITY... $probabilityOfSuccessfulAttack")

      // calculation of the maximum safe transaction value
      B = VConf.blockReward
      o = VConf.attackDuration
      alpha = VConf.discountOnStolenGoods
      k = VConf.amountOfAttackedMerchants
      attackDuration = VConf.attackDuration
      maximalSafeTransactionValue = ((o * (1 - r) * B) / (k * (alpha + r - 1))).toInt
      logger.debug(s"MAXIMAL SAFE TRANSACTION VALUE... $maximalSafeTransactionValue")

      if (VConf.attackFailed) {
        attackSucceeded = -1
        logger.debug(s"RESULT: ATTACK FAILED")
      } else if (VConf.attackSuccessful) {
        successfulAttackInBlocks = VConf.attackSuccessfulInBlocks
        attackSucceeded = 1
        logger.debug(s"RESULT: ATTACK IS SUCCESSFUL")
      } else {
        logger.debug(s"RESULT: ATTACK YET UNDECIDED")
      }

      // zero confirmations -> always successful attack
      if (VConf.confirmations == 0) {
        logger.debug(s"ATTACK SUCCESSFUL DUE TO ZERO CONFIRMATIONS")
      }

      maliciousBlockchainLength = VConf.evilChainLength
      goodBlockchainLength = VConf.goodChainLength
      logger.debug(s"GOOD BLOCKCHAIN LENGTH... $goodBlockchainLength")
      logger.debug(s"EVIL BLOCKCHAIN LENGTH... $maliciousBlockchainLength")
    }

    var staleBlocks = 0
    if(VConf.attackSuccessful) {
      staleBlocks = Math.max(blocks.size - longestChainLength - goodBlockchainLength,0)
    } else {
      // malicious chain can be longer despite not winning
      staleBlocks = Math.max(blocks.size - longestChainLength - maliciousBlockchainLength,0)
    }
    logger.debug(s"STALE BLOCKS... $staleBlocks")

    var confirmedFloodAttackTransactions = 0
    var floodAttackSpentTransactionFees = 0
    var confirmedTransactionsBelowTargetTransactionFee = 0
    if (VConf.strategy == "BITCOIN_LIKE_BLOCKCHAIN" && VConf.floodAttackTransactionFee > 0) {
      confirmedFloodAttackTransactions = longestChain.flatMap(_.transactions).count(_.isFloodAttack)
      logger.debug(s"CONFIRMED FLOOD ATTACK TRANSACTIONS... $confirmedFloodAttackTransactions")
      floodAttackSpentTransactionFees = longestChain.flatMap(_.transactions).count(_.isFloodAttack) * VConf.floodAttackTransactionFee
      logger.debug(s"SPENT TRANSACTIONS FEES... $floodAttackSpentTransactionFees IN SATOSHI")
      confirmedTransactionsBelowTargetTransactionFee = longestChain.flatMap(_.transactions).count(_.transactionFee < VConf.floodAttackTransactionFee)
      logger.debug(s"CONFIRMED TRANSACTIONS BELOW TARGET TRANSACTION FEE... $confirmedTransactionsBelowTargetTransactionFee")
    }

    ReducerIntermediateResult(
      events.sortBy(_.timestamp),
      duration,
      longestChainLength,
      longestChainSize,
      longestChainNumberTransactions,
      timesAvgWithOutliers,
      timesAvgNoOutliers,
      firstBlockNumberOfRecipients,
      lastBlockNumberOfRecipients,
      VConf.nonSegWitMaxTransactionsPerBlock,
      VConf.segWitMaxTransactionsPerBlock,
      nonSegWitMaxTPS,
      segWitMaxTPS,
      segWitMaxBlockWeight,
      transactions,
      staleBlocks,
      attackSucceeded,
      successfulAttackInBlocks,
      probabilityOfSuccessfulAttack,
      maximalSafeTransactionValue,
      maliciousBlockchainLength,
      goodBlockchainLength,
      attackDuration,
      B,
      o,
      alpha,
      k,
      actualTPS,
      avgBlockTime,
      simulationStart,
      confirmedFloodAttackTransactions,
      floodAttackSpentTransactionFees,
      confirmedTransactionsBelowTargetTransactionFee
    )
  }
}
