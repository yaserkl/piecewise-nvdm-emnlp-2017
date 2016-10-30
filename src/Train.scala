//General imports
import java.io.File
import java.util.ArrayList
import java.util.Random

import BIDMat.SciFunctions._
import YADLL.FunctionGraph.Graph.OGraph
import YADLL.FunctionGraph.Operators.SpecOps.KL_Gauss
import YADLL.FunctionGraph.Optimizers.{SGOpt, _}
import YADLL.FunctionGraph.Theta
import YAVL.Data.Text.Lexicon.Lexicon
import YAVL.Utils.{Logger, ScalaDebugUtils}

import scala.runtime.{RichInt, RichInt$}
//Imports from BIDMat
import BIDMat.{CMat, CSMat, DMat, Dict, FMat, GIMat, GMat, GSMat, HMat, IDict, IMat, Mat, SBMat, SDMat, SMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
//Imports from the library YADLL v0.7
import YADLL.FunctionGraph.BuildArch
import YADLL.Utils.ConfigFile
/**
  * Created by ago109 on 10/27/16.
  */
object Train {
  Mat.checkMKL //<-- needed in order to check for BIDMat on cpu or gpu...

  //def buildDataChunks(rng : Random, sampler : DocSampler, blockSize : Int)
  def buildDataChunks(sampler : DocSampler):ArrayList[(Mat,Mat)]={
    val chunks = new ArrayList[(Mat,Mat)]()
    while(sampler.isDepleted() == false){
      val batch = sampler.drawFullDocBatch() //drawMiniBatch(blockSize, rng)
      chunks.add(batch)
    }
    return chunks
  }

  /**
    *
    * @param rng
    * @param graph
    * @param dataChunks
    * @param numModelSamples -> num samples to compute expectation of lower-bound over for model
    * @return (doc.nll, loss, KL-gaussian-score, KL-piecewise-score)
    */
  def evalModel(rng : Random, graph : OGraph, dataChunks : ArrayList[(Mat,Mat)], numModelSamples : Int = 10):Array[Mat] ={
    graph.hardClear()
    val numDocs = dataChunks.size() * 1f
    val stats =new Array[Mat](3)
    var doc_nll:Mat = 0f
    var KL_gauss_score:Mat = 0f
    var KL_piece_score:Mat = 0f
    var numDocsSeen = 0
    val n_lat = graph.getOp("z").dim //we need to know # of latent variables in model
    var i = 0
    while(i < dataChunks.size()){
      val batch = dataChunks.get(i)
      val x = batch._1.asInstanceOf[Mat]
      val y = batch._2.asInstanceOf[Mat]
      //val L_n = sum((x > 0f),1) //get per-document lengths
      val L_n = x.ncols * 1f //get per-document lengths
      val numSamps = x.ncols * 1f
      var s = 0
      var log_probs: Mat = null
      var KL_gauss_s:Mat = 0f
      var KL_piece_s:Mat = 0f
      while(s < numModelSamples) {
        //Evaluate model on x using y as the target
        graph.clamp(("x-in", x))
        graph.clamp(("x-targ", y))

        //Generate random samples for model is needed
        if (graph.modelTypeName.contains("hybrid")) {
          val eps_gauss: Mat = normrnd(0f, 1f, n_lat, x.ncols)
          val eps_piece: Mat = rand(n_lat, x.ncols)
          graph.clamp(("eps-gauss", eps_gauss))
          graph.clamp(("eps-piece", eps_piece))
        } else if (graph.modelTypeName.contains("gaussian")) {
          val eps_gauss: Mat = normrnd(0f, 1f, n_lat, x.ncols)
          graph.clamp(("eps-gauss", eps_gauss))
        } else if (graph.modelTypeName.contains("piece")) {
          val eps_piece: Mat = rand(n_lat, x.ncols)
          graph.clamp(("eps-piece", eps_piece))
        }

        //Run inference & estimate posterior probabilities
        graph.eval()
        if (graph.modelTypeName.contains("vae")) {
          //If model is variational, use lower-bound to get probs
          val P_theta = sum(graph.getStat("x-out") *@ graph.getStat("x-targ"), 1) //P_\Theta
          var KL_term: Mat = null
          if (graph.modelTypeName.contains("hybrid")) {
            val KL_gauss = graph.getOp("KL-gauss").per_samp_result
            val KL_piece = graph.getOp("KL-piece").per_samp_result
            KL_term = KL_gauss + KL_piece
            KL_gauss_s += graph.getStat("KL-gauss") *@ numSamps
            KL_piece_s += graph.getStat("KL-piece") *@ numSamps
          } else if (graph.modelTypeName.contains("gaussian")) {
            KL_term = graph.getOp("KL-gauss").per_samp_result
            KL_gauss_s += graph.getStat("KL-gauss") *@ numSamps
          } else if (graph.modelTypeName.contains("piece")) {
            KL_term = graph.getOp("KL-piece").per_samp_result
            KL_piece_s += graph.getStat("KL-piece") *@ numSamps
          }
          val vlb = ln(P_theta) - KL_term //variational lower bound log P(X) = (ln P_Theta - KL)
          if(null != log_probs){
            log_probs = log_probs + vlb
          }else
            log_probs = vlb //user vlb in place of intractable distribution
        } else {
          //If model is NOT variational, use its decoder's posterior
          log_probs = graph.getStat("L") *@ numSamps
        }
        s += 1
        if(graph.getOp("h1") != null){
          graph.toggleFreezeOp("h1",true)
        }else{
          graph.toggleFreezeOp("h0",true)
        }
      }
      numDocsSeen += 1
      log_probs = log_probs / (numModelSamples * 1f) // get E[VLB]
      doc_nll += (sum(log_probs) / L_n)
      KL_gauss_score += (KL_gauss_s / (numModelSamples * 1f))
      KL_piece_score += (KL_piece_s / (numModelSamples * 1f))

      graph.hardClear()
      if(graph.getOp("h1") != null){
        graph.toggleFreezeOp("h1",false)
      }else{
        graph.toggleFreezeOp("h0",false)
      }
      print("\r > "+numDocsSeen + " docs seen...")
      i += 1
    }
    println()
    stats(0) = -doc_nll / (1f * numDocs)
    stats(1) = KL_gauss_score /// (1f * numDocs)
    stats(2) = KL_piece_score  /// (1f * numDocs)
    return stats
  }

  def main(args : Array[String]): Unit ={
    if(args.length != 1){
      System.err.println("usage: [/path/to/configFile.cfg]")
      return
    }
    val configFile = new ConfigFile(args(0)) //grab configuration from local disk
    val archBuilder = new BuildArch()
    //Get simulation meta-parameters from config
    val seed = configFile.getArg("seed").toInt
    setseed(seed) //controls determinism of overall simulation
    val rng = new Random(seed)
    val dataFname = configFile.getArg("dataFname")
    val dictFname = configFile.getArg("dictFname")
    val dict = new Lexicon(dictFname)
    println(" > Vocab |V| = "+dict.getLexiconSize())
    val trainModel = configFile.getArg("trainModel").toBoolean
    var graphFname = configFile.getArg("graphFname")

    var outputDir:String = null
    if(graphFname.contains("/")){ //extract output directory from graph fname if applicable...
      outputDir = graphFname.substring(0,graphFname.lastIndexOf("/")+1)
      val dir = new File(outputDir)
      dir.mkdir() //<-- build dir on disk if it doesn't already exist...
      graphFname = graphFname.substring(graphFname.indexOf("/")+1)
    }else{
      outputDir = "tmp_model_out/"
      val dir = new File(outputDir)
      dir.mkdir() //<-- build dir on disk if it doesn't already exist...
    }

    //Read in config file to get all program meta-parameters
    archBuilder.readConfig(configFile)
    archBuilder.n_in = dict.getLexiconSize() //input/output-dim is = to |V|

    //Build a sampler for main data-set
    val sampler = new DocSampler(dataFname,dict)
    sampler.loadDocsFromLibSVMoCache()

    if(trainModel){ //train/fit model to data
      val miniBatchSize = configFile.getArg("miniBatchSize").toInt
      val numEpochs = configFile.getArg("numEpochs").toInt
      val validFname = configFile.getArg("validFname")
      val errorMark = configFile.getArg("errorMark").toInt
      val norm_rescale = configFile.getArg("norm_rescale").toFloat
      val optimizer = configFile.getArg("optimizer")
      val lr = configFile.getArg("lr").toFloat
      val patience = configFile.getArg("patience").toInt
      val lr_div = configFile.getArg("lr_div").toFloat
      val epoch_bound = configFile.getArg("epoch_bound").toInt
      val gamma_iter_bound = configFile.getArg("gamma_iter_bound").toInt
      //Build validation set to conduct evaluation
      var validSampler = new DocSampler(validFname,dict)
      validSampler.loadDocsFromLibSVMoCache()
      val dataChunks = Train.buildDataChunks(validSampler)
      validSampler = null //toss aside this sampler for garbage collection

      //Build Ograph given config
      val graph = archBuilder.autoBuildGraph()
      graph.saveOGraph(outputDir+graphFname)
      val n_lat = graph.getOp("z").dim

      //Build optimizer given config
      var opt:Opt = null
      var climb_type = "descent"
      if(archBuilder.archType.contains("vae")){
        climb_type = "ascent" //training a VAE requires gradient ascent!!
      }
      if(optimizer.compareTo("rmsprop") == 0){
        opt = new RMSProp(lr=lr,opType=climb_type)
      }else if(optimizer.compareTo("adam") == 0){
        opt = new ADAM(lr=lr,opType=climb_type)
      }else if(optimizer.compareTo("nadam") == 0){
        opt = new NADAM(lr=lr,mu = 0.99f, opType=climb_type)
      }else if(optimizer.compareTo("radam") == 0){
        opt = new RADAM(lr=lr,opType=climb_type)
      }else if(optimizer.compareTo("adagrad") == 0){
        opt = new AdaGrad(lr=lr,opType=climb_type)
      }else{ //default is good ol' SGD
        opt = new SGOpt(lr=lr,opType=climb_type)
      }
      opt.norm_threshold = norm_rescale

      println(" ++++ Model: " + graph.modelTypeName + " Properties ++++ ")
      println("  # Inputs = "+graph.getOp("x-in").dim)
      println("  # Lyr 1 Hiddens = "+graph.getOp("h0").dim)
      if(archBuilder.n_hid_2 > 0){
        println("  # Lyr 2 Hiddens = "+graph.getOp("h1").dim)
      }
      println("  # Latents = "+graph.getOp("z").dim)
      println("  # Outputs = "+graph.getOp("x-out").dim)
      println(" ++++++++++++++++++++++++++ ")

      val logger = new Logger(outputDir + "error_stat.log")
      logger.openLogger()
      logger.writeStringln("Epoch, Valid.NLL, Valid.PPL, KL-Gauss, KL-Piece")

      var stats = Train.evalModel(rng,graph,dataChunks)
      var bestNLL = stats(0)
      var bestPPL = exp(bestNLL)
      println("-1 > NLL = "+bestNLL + " PPL = " + bestPPL + " KL.G = "+stats(1) + " KL.P = "+stats(2))
      logger.writeStringln("-1"+","+bestNLL+","+bestPPL+","+stats(1)+","+stats(2)+",NA")

      //Actualy train model
      var totalNumIter = 0
      val gamma_delta = (1f - archBuilder.vae_gamma)/(gamma_iter_bound*1f)
      var impatience = 0
      var epoch = 0
      while(epoch < numEpochs) {
        if(epoch == (numEpochs-1)){
          opt.setPolyakAverage()
        }
        var numSampsSeen = 0 // # samples seen w/in an epoch
        var mark = 1
        var avg_update_time = 0f
        var numIter = 0
        var currNLL:Mat = bestNLL
        var currPPL:Mat = bestPPL
        while (sampler.isDepleted() == false) {
          val t0 = System.nanoTime()
          /* ####################################################
           * Gather & clamp data/samples to OGraph
           * ####################################################
           */
          val batch = sampler.drawMiniBatch(miniBatchSize, rng)
          val x = batch._1.asInstanceOf[Mat]
          val y = batch._2.asInstanceOf[Mat]
          val numSamps = y.ncols
          numSampsSeen += numSamps
          numIter += 1
          totalNumIter += 1
          graph.clamp(("x-in",x))
          graph.clamp(("x-targ",y))

          //Generate random samples for model is needed
          if(graph.modelTypeName.contains("hybrid")){
            val eps_gauss:Mat = normrnd(0f,1f,n_lat,x.ncols)
            val eps_piece:Mat = rand(n_lat,x.ncols)
            graph.clamp(("eps-gauss",eps_gauss))
            graph.clamp(("eps-piece",eps_piece))
          }else if(graph.modelTypeName.contains("gaussian")){
            val eps_gauss:Mat = normrnd(0f,1f,n_lat,x.ncols)
            graph.clamp(("eps-gauss",eps_gauss))
          }else if(graph.modelTypeName.contains("piece")){
            val eps_piece:Mat = rand(n_lat,x.ncols)
            graph.clamp(("eps-piece",eps_piece))
          }

          /* ####################################################
           * Run inference under model given data/samples
           * ####################################################
           */
          graph.eval()

          /* ####################################################
           * Estimate parameter-gradients
           * ####################################################
           */
          val grad = graph.calc_grad()

          /* ####################################################
           * Update model given gradients
           * ####################################################
           */
          opt.update(theta = graph.theta, nabla = grad, miniBatchSize = numSamps)

          if(gamma_iter_bound > 0){
            val gamma = Math.min(1f,graph.theta.getParam("gamma").dv.toFloat + gamma_delta)
            graph.theta.setParam("gamma",gamma)
          }

          val t1 = System.nanoTime()
          avg_update_time += (t1 - t0)

          if(errorMark > 0 && numSampsSeen >= (mark * errorMark)){ //eval model @ this point
            stats = Train.evalModel(rng,graph,dataChunks)
            currNLL = stats(0)
            //logger.writeStringln("Epoch, Valid.NLL, Valid.PPL, KL-Gauss, KL-Piece")
            currPPL = exp(currNLL)
            if(currNLL.dv.toFloat <= bestNLL.dv.toFloat){
              bestNLL = currNLL
              bestPPL = currPPL
              graph.theta.saveTheta(outputDir+"best_at_epoch_"+epoch)
            }else{
              if(epoch >= epoch_bound) {
                impatience += 1
                if (impatience >= patience) {
                  opt.stepSize = opt.stepSize / lr_div
                  impatience = 0
                }
              }
            }
            mark += 1
            println("\n "+epoch+" >> NLL = "+currNLL + " PPL = " + currPPL + " KL.G = "+stats(1) + " KL.P = "+stats(2) + " over "+numSampsSeen + " samples")
            logger.writeStringln(""+epoch+","+currNLL+","+currPPL+","+stats(1)+","+stats(2)+","+(avg_update_time/numIter * 1e-9f))
          }
          print("\r "+epoch+" > NLL = "+currNLL + " PPL = " + currPPL + " T = "+ (avg_update_time/numIter * 1e-9f)
            + " s, over "+numSampsSeen + " samples")
          //println("Alpha.Mu = "+graph.getStat("alpha_mu"))
          //println("Alpha.Sigma = "+graph.getStat("alpha_sigma"))
        }
        println()
        //Checkpoint save current \Theta of model
        graph.theta.saveTheta(outputDir+"check_epoch_"+epoch)

        var polyak_avg:Theta = null
        if(epoch == (numEpochs-1)){
          println(" >> Estimating Polyak average over Theta...")
          polyak_avg = opt.estimatePolyakAverage()
          polyak_avg.saveTheta(outputDir+"polyak_avg")
        }

        //Eval model after an epoch
        stats = Train.evalModel(rng,graph,dataChunks)
        currNLL = stats(0)
        currPPL = exp(currNLL)
        if(currNLL.dv.toFloat <= bestNLL.dv.toFloat) {
          bestNLL = currNLL
          bestPPL = currPPL
        }else{
          if(epoch >= epoch_bound) {
            impatience += 1
            if (impatience >= patience) {
              opt.stepSize = opt.stepSize / lr_div
              impatience = 0
            }
          }
        }
        logger.writeStringln(""+epoch+","+currNLL+","+currPPL+","+stats(1)+","+stats(2)+","+(avg_update_time/numIter * 1e-9f))
        println(epoch+" > NLL = "+currNLL + " PPL = " + currPPL + " KL.G = "+stats(1) + " KL.P = "+stats(2))
        epoch += 1
        sampler.reset()
      }
    }else{ //evaluation only
      val thetaFname = configFile.getArg("thetaFname")
      println(" > Loading Theta: "+thetaFname)
      val theta = archBuilder.loadTheta(thetaFname)
      //Load graph given config
      println(" > Loading OGraph: "+graphFname)
      val graph = archBuilder.loadOGraph(graphFname)
      graph.theta = theta
      graph.hardClear() //<-- clear out any gunked up data from previous sessions
      println(" > Building data-set...")
      val dataChunks = Train.buildDataChunks(sampler)
      println(" > Evaluating model on data-set...")
      //graph.muteEvals(true,"L") //avoid calculating outermost-loss
      val stats = Train.evalModel(rng,graph,dataChunks)
      val nll = stats(0)
      val ppl = exp(nll)
      println(" ====== Performance ======")
      println(" > Corpus.NLL = "+nll)
      println(" > Corpus.PPL = "+ppl)
      println(" > over "+ dataChunks.size() + " documents")
    }
  }


}
