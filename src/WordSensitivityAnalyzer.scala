//General imports
import java.io.File
import java.util
import java.util.ArrayList
import java.util.Random
import scala.io.StdIn.{readLine,readInt}

import BIDMat.FMat
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import YADLL.FunctionGraph.Graph.OGraph
import YADLL.FunctionGraph.Operators.SpecOps.{KL_Gauss, KL_Piece}
import YADLL.FunctionGraph.Optimizers.{SGOpt, _}
import YADLL.FunctionGraph.Theta
import YADLL.Utils.{MathUtils, MiscUtils}
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
  * Computes gradients of words w/ respect to Kl terms (either G-KL, P-KL, or both)
  * and ranks based on strength of gradients (i.e., L2-norms).
  *
  * Created by ago109 on 11/15/16.
  */
object WordSensitivityAnalyzer {
  Mat.checkMKL //<-- needed in order to check for BIDMat on cpu or gpu...

  //blockDerivFlow
  def main(args : Array[String]): Unit ={
    if(args.length != 1){
      System.err.println("usage: [/path/to/config.cfg]")
      return
    }
    //Extract arguments
    val configFile = new ConfigFile(args(0)) //grab configuration from local disk
    val seed = configFile.getArg("seed").toInt
    setseed(seed) //controls determinism of overall simulation
    val dataFname = configFile.getArg("dataFname")
    val dictFname = configFile.getArg("dictFname")
    val graphFname = configFile.getArg("graphFname")
    val thetaFname = configFile.getArg("thetaFname")
    val labelFname = configFile.getArg("labelFname")
    val top_k = configFile.getArg("top_k").toInt

    //Load in labels (1 per doc)
    val fd_lab = MiscUtils.loadInFile(labelFname)
    val labs = new ArrayList[Int]()
    var line = fd_lab.readLine()
    var numRead = 0
    while(line != null){
      labs.add(line.replaceAll(" ","").toInt)
      numRead += 1
      print("\r > "+numRead + " labels read...")
      line = fd_lab.readLine()
    }
    println()

    //Prop up sampler/lexicon
    val dict = new Lexicon(dictFname)
    val sampler = new DocSampler(dataFname,dict)
    sampler.loadDocsFromLibSVMToCache()

    //Load graph given config
    val archBuilder = new BuildArch()
    println(" > Loading Theta: "+thetaFname)
    val theta = archBuilder.loadTheta(thetaFname)
    println(" > Loading OGraph: "+graphFname)
    val graph = archBuilder.loadOGraph(graphFname)
    graph.theta = theta
    graph.hardClear() //<-- clear out any gunked up data from previous sessions

    //we need to know # of latent variables in model
    var n_lat = graph.getOp("z").dim
    if(graph.getOp("z-gaussian") != null){
      n_lat = graph.getOp("z-gaussian").dim
    }else if(graph.getOp("z-piece") != null){
      n_lat = graph.getOp("z-piece").dim
    }

    print(" > Enter a label-index (-1 to quit): ")
    var lab_select = readInt()
    println()
    //TODO: user select a label
    while(lab_select >= 0){ //infinite loop unless user inputs -1 (or < 0, for exit)
      var docIdx = WordSensitivityAnalyzer.getDocPtr(lab_select,labs)
      if(docIdx < 0){
        println(" > No more docs of type = "+lab_select)
      }else{
        val doc = sampler.getDocAt(docIdx)
        val x = doc.getBOWVec()
        //val x_i = sparse(w_idx,0,1f,doc.dim,1) //create a useless y or target
        val x_0 = sparse(0,0,1f,doc.dim,1) //create a useless y or target
        graph.getOp("x-in").muteDerivCalc(flag = false) //we want the derivative w/ respect to word inputs
        graph.clamp(("x-in", x))
        graph.clamp(("x-targ", x_0))
        //Generate random sample for model as needed
        val eps_gauss: Mat = ones(n_lat,x.ncols) *@ normrnd(0f, 1f, n_lat, 1)
        val eps_piece: Mat = ones(n_lat,x.ncols) *@ rand(n_lat, 1)
        val numDocs = 1 //<-- all of the predictions are for a single document
        val KL_correction:Mat = ones(1,x.ncols) *@ x.ncols //correct for KL being copied equal to # of predictions

        graph.clamp(("eps-gauss", eps_gauss))
        graph.clamp(("eps-piece", eps_piece))
        graph.clamp(("KL-correction",KL_correction))
        graph.clamp(("N",numDocs)) //<-- note we don't want a mean loss this time...
        graph.eval() //do inference to gather statistics
        val KL_gauss_op = graph.getOp("KL-gauss")
        if(KL_gauss_op != null){
          println(" Top("+top_k+") Most Sensitive to G-KL:")
          //Block gradient flow at the latent variable z!
          graph.blockDerivFlow(graph.getOp("z"))
          graph.blockDerivFlow(KL_gauss_op)
          val nabla = graph.calc_grad()
          var word_grad = nabla.getParam("x-in") //get derivative of each term w/ respect to input words
          //Now build a ranked list based on gradients for each word in document
          val stat = sortdown2(word_grad) //highest/most positive values at top
          word_grad = stat._1.asInstanceOf[Mat]
          val sortedInd = stat._2.asInstanceOf[IMat]
          var  i = 0
          while(i < top_k){
            val score = FMat(word_grad)(i,0)
            val idx = sortedInd(i,0)
            println(dict.getSymbol(idx) + " = "+score)
            i += 1
          }

          graph.unblockDerivs()
        }
        val KL_piece_op = graph.getOp("KL-piece")
        if(KL_piece_op != null){
          println(" Top("+top_k+") Most Sensitive to P-KL:")
          //Block gradient flow at the latent variable z!
          graph.blockDerivFlow(graph.getOp("z"))
          graph.blockDerivFlow(KL_piece_op)
          val nabla = graph.calc_grad()
          //Now build a ranked list based on gradients for each word in document
          var word_grad = nabla.getParam("x-in") //get derivative of each term w/ respect to input words
          //Now build a ranked list based on gradients for each word in document
          val stat = sortdown2(word_grad) //highest/most positive values at top
          word_grad = stat._1.asInstanceOf[Mat]
          val sortedInd = stat._2.asInstanceOf[IMat]
          var  i = 0
          while(i < top_k){
            val score = FMat(word_grad)(i,0)
            val idx = sortedInd(i,0)
            println(dict.getSymbol(idx) + " = "+score)
            i += 1
          }
          graph.unblockDerivs()
        }
      }
      print(" > Enter a label-index (-1 to quit): ")
      lab_select = readInt()
      println()
    }
  }

  /**
    *
    * @param lab
    * @param list
    * @return -1 if no such doc w/ lab idx found...
    */
  def getDocPtr(lab : Int, list : ArrayList[Int]):Int={
    var i = 0
    var stayInLoop = true
    var labValue = -1
    var idx = -1
    while(i < list.size() && stayInLoop){
      labValue = list.get(i)
      if(labValue == lab){
        idx = i //record index of removed doc/record
        stayInLoop = false
      }
      i += 1
    }
    if(labValue >= 0){
      list.remove(idx)
    }
    return labValue
  }


}