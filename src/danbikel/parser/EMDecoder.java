package  danbikel.parser;

import java.util.HashMap;
import danbikel.util.*;
import danbikel.lisp.*;
import java.io.*;
import java.util.*;
import java.rmi.*;


/**
 * Provides the methods necessary to perform CKY parsing on input sentences.
 */
public class EMDecoder extends Decoder {
  // debugging constants
  // debugging code will be optimized away when the following booleans are false
  private final static boolean debug = false;
  private final static boolean debugPrunedPretermsPosMap = false;
  private final static boolean debugPrunedPunctuationPosMap = false;
  private final static boolean debugSentenceSize = true;
  private final static boolean debugSpans = false;
  private final static boolean debugInit = true;
  private final static boolean debugTop = false;
  private final static boolean debugComplete = false;
  private final static boolean debugJoin = false;
  private final static boolean debugStops = false;
  private final static boolean debugUnaries = false;
  private final static boolean debugUnariesAndStopProbs = false;
  private final static boolean debugConstraints = false;
  private final static boolean debugAnalyzeChart = false;
  private final static String debugGoldFilenameProperty =
    "parser.debug.goldFilename";
  private final static boolean debugAnalyzeBestDerivation = false;
  private final static boolean debugOutputChart = false;
  private final static String debugChartFilenamePrefix = "chart";
  private final static boolean debugCommaConstraint = false;
  private final static boolean debugDontPostProcess = false;
  /**
   * This debugging option should be used only when the property
   * <tt>parser.model.precomputeProbabilities</tt> was <tt>false</tt>
   * during training (and should therefore be <tt>false</tt> during
   * decoding as well).  This is the most verbose of the debugging
   * options, so expect an output file on the order of tens of
   * megabytes, if not larger.
   */
  private final static boolean debugOutputAllCounts = false;
  private final static Symbol S = Symbol.add("S");
  private final static Symbol SINV = Symbol.add("SINV");
  private final static Symbol PRN = Symbol.add("PRN");
  private final static Symbol RRB = Symbol.add("-RRB-");
  private final static Symbol NP = Symbol.add("NP");
  private final static Symbol NPA = Symbol.add("NP-A");
  private final static Symbol RRC = Symbol.add("RRC");
  private final static Symbol VP = Symbol.add("VP");
  private final static Symbol CC = Symbol.add("CC");
  private final static Symbol comma = Symbol.add(",");
  private final static Symbol FRAG = Symbol.add("FRAG");
  private final static Symbol willSym = Symbol.add("will");
  private final static Symbol mdSym = Symbol.add("MD");

  // constants
  private final static String className = EMDecoder.class.getName();

  /**
   * A list containing only {@link Training#startSym()}, which is the
   * type of list that should be used when there are zero real previous
   * modifiers (to start the Markov modifier process).
   */
  private final SexpList startList = Trainer.newStartList();
  private final WordList startWordList = Trainer.newStartWordList();

  // data members
  // data members used when debugSentenceSize is true
  private float avgSentLen = 0.0f;
  private int numSents = 0;

  // data member to use when debugAnalyzeChart is true
  // (and when the property "parser.debug.goldFilename" has been set)
  private SexpTokenizer goldTok;

  /**
   * Constructs a new decoder that will use the specified
   * <code>DecoderServer</code> to get all information and probabilities
   * required for decoding (parsing).
   *
   * @param id the id of this parsing client
   * @param  server the <code>DecoderServerRemote</code> implementor
   * (either local or remote) that provides this decoder object with
   * information and probabilities required for decoding (parsing)
   */
  public EMDecoder(int id, DecoderServerRemote server) {
    super(id, server);
  }

  protected boolean isPuncRaiseWord(Sexp word) {
    return prunedPunctuationPosMap.containsKey(word);
  }

  protected void preProcess(SexpList sentence, SexpList tags)
  throws RemoteException {
    // preserve original sentence
    originalSentence.clear();
    originalSentence.addAll(sentence);

    originalWords.clear();
    originalWords.addAll(sentence);

    // eliminate pruned words
    for (int i = sentence.length() - 1; i >= 0; i--) {
      Symbol word = (downcaseWords ?
                     Symbol.get(sentence.get(i).toString().toLowerCase()) :
                     sentence.symbolAt(i));
      Symbol tag = tags == null ? null : tags.listAt(i).first().symbol();
      if (tag != null ? prunedPretermsPosSet.contains(tag) :
	                (prunedPretermsPosMap.containsKey(word) &&
			 !word.toString().equals("'"))) {
        sentence.remove(i);
        originalWords.remove(i);
        if (tags != null)
          tags.remove(i);
      }
    }

    sentence = server.convertUnknownWords(sentence);

    // downcase words
    int sentLen = sentence.length();
    if (downcaseWords) {
      for (int i = 0; i < sentLen; i++) {
        if (sentence.get(i).isList()) // skip unknown words
          continue;
        Symbol downcasedWord =
          Symbol.add(sentence.symbolAt(i).toString().toLowerCase());
        sentence.set(i, downcasedWord);
      }
    }


    // remove intitial and final punctuation "words"
    for (int i = 0; i < sentence.length() - 1; i++) {
      if (sentence.get(i).isList())
        break;
      if (isPuncRaiseWord(sentence.get(i))) {
        sentence.remove(i);
        originalWords.remove(i);
        if (tags != null)
          tags.remove(i);
        i--;
      }
      else
        break;
    }
    for (int i = sentence.length() - 1; i > 0; i--) {
      if (sentence.get(i).isList())
        break;
      if (isPuncRaiseWord(sentence.get(i))) {
        sentence.remove(i);
        originalWords.remove(i);
        if (tags != null)
          tags.remove(i);
      }
      else
        break;
    }
  }

  protected void postProcess(Sexp tree) {
    restoreOriginalWords(tree, 0);
    if (debugDontPostProcess)
      return;
    else
      Language.training.postProcess(tree);
  }

  /**
   * Restores the original words in the current sentence.
   *
   * @param tree the sentence for which to restore the original words,
   * cached during execution of {@link #preProcess}
   * @param wordIdx a threaded word index
   * @return the current value of the monotonically-increasing word index,
   * after replacing all words in the current subtree
   */
  protected int restoreOriginalWords(Sexp tree, int wordIdx) {
    Treebank treebank = Language.treebank;
    if (treebank.isPreterminal(tree))
      ;
    else if (tree.isList()) {
      SexpList treeList = tree.list();
      int treeListLen = treeList.length();
      for (int i = 1; i < treeListLen; i++) {
        Sexp currChild = treeList.get(i);
        if (treebank.isPreterminal(currChild)) {
          Word word = treebank.makeWord(currChild);
          word.setWord(originalWords.symbolAt(wordIdx++));
          treeList.set(i, treebank.constructPreterminal(word));
        }
        else
          wordIdx = restoreOriginalWords(currChild, wordIdx);
      }
    }
    return wordIdx;
  }

  /**
   * Caches the locations of commas to be used for the comma constraint in the
   * boolean array {@link #commaForPruning}.  Also, sets up an array
   * (initialized to be entirely false) of booleans to cache the locations of
   * conjunctions, determined within {@link #initialize(SexpList,SexpList)}
   * (hence, the initialization of the {@link #conjForPruning} array is not
   * complete until after {@link #initialize(SexpList,SexpList)} has finished
   * executing).
   */
  protected void setCommaConstraintData() {
    if (commaForPruning == null || sentLen > commaForPruning.length)
      commaForPruning = new boolean[sentLen];
    boolean withinParens = false;
    for (int i = 0; i < sentLen; i++) {
      Symbol word = getSentenceWord(i);
      if (Language.treebank.isLeftParen(word))
	withinParens = true;
      else if (Language.treebank.isRightParen(word))
	withinParens = false;
      commaForPruning[i] = !withinParens && Language.treebank.isComma(word);
    }

    if (conjForPruning == null || sentLen > conjForPruning.length)
      conjForPruning = new boolean[sentLen];
    for (int i = 0; i < sentLen; i++)
      conjForPruning[i] = false;
  }

  /**
   * Initializes the chart for parsing the specified sentence.  Specifically,
   * this method will add a chart item for each possible part of speech for
   * each word.
   *
   * @param sentence the sentence to parse, which must be a list containing
   * only symbols as its elements
   */
  protected void initialize(SexpList sentence) throws RemoteException {
    initialize(sentence, null);
  }

  /**
   * Initializes the chart for parsing the specified sentence, using the
   * specified coordinated list of part-of-speech tags when assigning parts
   * of speech to unknown words.
   *
   * @param sentence the sentence to parse, which must be a list containing
   * only symbols as its elements
   * @param tags a list that is the same length as <code>sentence</code> that
   * will be used when seeding the chart with the parts of speech for unknown
   * words; each element <i>i</i> of <code>tags</code> should itself be a
   * <code>SexpList</code> containing all possible parts of speech for the
   * <i>i</i><sup>th</sup> word in <code>sentence</code>; if the value of this
   * argument is <code>null</code>, then for each unknown word (or feature
   * vector), all possible parts of speech observed in the training data for
   * that unknown word will be used
   */
  protected void initialize(SexpList sentence, SexpList tags)
  throws RemoteException {

    preProcess(sentence, tags);

    if (debugInit) {
      System.err.println(className + ": sentence to parse: " + sentence);
    }

    this.sentence = sentence;
    sentLen = sentence.length();

    if (useCommaConstraint)
      setCommaConstraintData();

    HashSet tmpSet = new HashSet();

    for (int i = 0; i < sentLen; i++) {
      boolean wordIsUnknown = sentence.get(i).isList();
      Symbol word = null, features = null;
      if (wordIsUnknown) {
	SexpList wordInfo = sentence.listAt(i);
	if (keepAllWords) {
	  features = wordInfo.symbolAt(1);
	  boolean neverObserved = wordInfo.symbolAt(2) == Constants.trueSym;
	  word = neverObserved ? features : wordInfo.symbolAt(0);
	}
	else {
	  // we *don't* set features, since when keepAllWords is false,
	  // we simply replace unknown words with their word feature vectors
	  word = wordInfo.symbolAt(1);
	}
      }
      else {
	// word is a known word, so just grab it
	word = sentence.symbolAt(i);
      }

      Symbol origWord = (wordIsUnknown ? sentence.listAt(i).symbolAt(0) : null);
      SexpList tagSet = null;
      if (wordIsUnknown) {
	if (useLowFreqTags && posMap.containsKey(origWord)) {
	  tagSet = (SexpList)posMap.get(origWord);
	  if (tags != null)
	    tagSet = setUnion(tagSet, tags.listAt(i), tmpSet);
	}
	else if (tags != null)
	  tagSet = tags.listAt(i);
	else
	  tagSet = (SexpList)posMap.get(word);
      }
      else {
	tagSet = (SexpList)posMap.get(word);
      }

      if (tagSet == null) {
	Symbol defaultFeatures = Language.wordFeatures.defaultFeatureVector();
	tagSet = (SexpList)posMap.get(defaultFeatures);
      }
      if (tagSet == null) {
	tagSet = SexpList.emptyList;
	System.err.println(className + ": warning: no tags for default " +
			   "feature vector " + word);
      }
      int numTags = tagSet.length();
      for (int tagIdx = 0; tagIdx < numTags; tagIdx++) {
        Symbol tag = tagSet.symbolAt(tagIdx);
        if (!posSet.contains(tag))
          System.err.println(className + ": warning: part of speech tag " +
                             tag + " not seen during training");
	if (useCommaConstraint)
	  if (Language.treebank.isConjunction(tag))
	    conjForPruning[i] = true;
        Word headWord = new Word(word, tag, features);
        CKYItem item = chart.getNewItem();
        PriorEvent priorEvent = lookupPriorEvent;
        priorEvent.set(headWord, tag);
        double logPrior = server.logPrior(id, priorEvent);
        double logProb = logPrior; // technically, logPrior + 0.0
        item.set(tag, headWord,
                 emptySubcat, emptySubcat, null, null,
                 null,
                 startList, startList,
                 i, i,
                 false, false, true,
                 0.0, logPrior, logProb);

        if (findAtLeastOneSatisfyingConstraint) {
          Constraint constraint = constraints.constraintSatisfying(item);
          if (constraint != null) {
            if (debugConstraints)
              System.err.println("assigning satisfied constraint " +
                                 constraint + " to item " + item);
            item.setConstraint(constraint);
          }
          else {
            if (debugConstraints)
              System.err.println("constraint " + constraint +
                                 " is NOT satisfied by item " + item);
            continue;
          }
        }
        chart.add(i, i, item);
      } // end for each tag
      addUnariesAndStopProbs(i, i);
    } // end for each word index
  }

  protected SexpList setUnion(SexpList l1, SexpList l2, Set tmpSet) {
    tmpSet.clear();
    for (int i = 0; i < l1.length(); i++)
      tmpSet.add(l1.get(i));
    for (int i = 0; i < l2.length(); i++)
      tmpSet.add(l2.get(i));
    SexpList union = new SexpList(tmpSet.size());
    Iterator it = tmpSet.iterator();
    while (it.hasNext())
      union.add((Sexp)it.next());
    return union;
  }

  protected Sexp parse(SexpList sentence) throws RemoteException {
    return parse(sentence, null);
  }

  protected Sexp parse(SexpList sentence, SexpList tags)
    throws RemoteException {
    return parse(sentence, tags, null);
  }

  protected Sexp parse(SexpList sentence, SexpList tags,
                       ConstraintSet constraints)
    throws RemoteException {

    if (debugOutputAllCounts)
      Debug.level = 21;

    sentenceIdx++;
    if (maxSentLen > 0 && sentence.length() > maxSentLen) {
      if (debugSentenceSize)
	System.err.println(className + ": current sentence length " +
			   sentence.length() + " is greater than max. (" +
			   maxSentLen + ")");
      return null;
    }

    if (constraints == null) {
      findAtLeastOneSatisfyingConstraint = isomorphicTreeConstraints = false;
    }
    else {
      this.constraints = constraints;
      findAtLeastOneSatisfyingConstraint =
        constraints.findAtLeastOneSatisfying();
      isomorphicTreeConstraints =
        findAtLeastOneSatisfyingConstraint && constraints.hasTreeStructure();
      if (debugConstraints)
        System.err.println(className + ": constraints: " + constraints);
    }

    chart.setSizeAndClear(sentence.length());
    initialize(sentence, tags);
    if (debugSentenceSize) {
      System.err.println(className + ": current sentence length: " + sentLen +
                         " word" + (sentLen > 1 ? "s" : ""));
      numSents++;
      avgSentLen = ((numSents - 1)/(float)numSents) * avgSentLen +
                   (float)sentLen / numSents;
      System.err.println(className + ": cummulative average length: " +
                         avgSentLen + " words");
    }
    for (int span = 2; span <= sentLen; span++) {
      if (debugSpans)
        System.err.println(className + ": span: " + span);
      int split = sentLen - span + 1;
      for (int start = 0; start < split; start++) {
        int end = start + span - 1;
        if (debugSpans)
          System.err.println(className + ": start: " + start + "; end: " + end);
        complete(start, end);
      }
    }

    double prevTopLogProb = chart.getTopLogProb(0, sentLen - 1);
    if (debugTop)
      System.err.println(className + ": highest probability item for " +
                         "sentence-length span (0," + (sentLen - 1) + "): " +
                         prevTopLogProb);
    chart.resetTopLogProb(0, sentLen - 1);
    addTopUnaries(sentLen - 1);

    // the chart mixes two types of items that cover the entire span
    // of the sentnece: those that have had their +TOP+ probability multiplied
    // in (with topSym as their label) and those that have not; if the
    // top-ranked item also has topSym as its label, we're done; otherwise,
    // we look through all items that cover the entire sentence and get
    // the highest-ranked item whose label is topSym (NO WE DO NOT, since
    // we reset the top-ranked item just before adding top unaries)
    CKYItem topRankedItem = null;
    CKYItem potentialTopItem = (CKYItem)chart.getTopItem(0, sentLen - 1);
    if (potentialTopItem != null && potentialTopItem.label() == topSym)
      topRankedItem = potentialTopItem;

    if (debugTop)
      System.err.println(className + ": top-ranked +TOP+ item: " +
                         topRankedItem);


    if (debugConstraints) {
      Iterator it = constraints.iterator();
      while (it.hasNext()) {
        Constraint c = (Constraint)it.next();
        System.err.println(className + ": constraint " + c + " has" +
                           (c.hasBeenSatisfied() ? " " : " NOT ") +
                           "been satisfied");
      }
    }

    /*
    if (topRankedItem == null) {
     double highestProb = Constants.logOfZero;
     Iterator it = chart.get(0, sentLen - 1);
     while (it.hasNext()) {
       CKYItem item = (CKYItem)it.next();
       if (item.label() != topSym)
	 continue;
       if (item.logProb() > highestProb) {
	 topRankedItem = item;
	 highestProb = item.logProb();
       }
     }
    }
    */

    if (debugAnalyzeChart) {
      Sexp goldTree = null;
      try {
	goldTree = Sexp.read(goldTok);
	if (goldTree != null) {
	  String prefix = "chart-debug (" + sentenceIdx + "): ";
	  danbikel.parser.util.DebugChart.findConstituents(prefix,
                                                           downcaseWords,
							   chart, topRankedItem,
                                                           sentence,
                                                           goldTree);
	}
	else
	  System.err.println(className + ": couldn't read gold parse tree " +
			     "for chart analysis of sentence " + sentenceIdx);
      }
      catch (IOException ioe) {
	System.err.println(className + ": couldn't read gold parse tree " +
			   "for chart analysis of sentence " + sentenceIdx);
      }
    }

    if (debugAnalyzeBestDerivation) {
      String prefix = "derivation-debug for sent. " + sentenceIdx + " (len=" +
	sentLen + "): ";
      danbikel.parser.util.DebugChart.printBestDerivationStats(prefix,
							       chart,
							       sentLen,
							       topSym,
							       prevTopLogProb,
							       topRankedItem);
    }

    if (debugOutputChart) {
      try {
	String chartFilename =
	  debugChartFilenamePrefix + "-" + id + "-" + sentenceIdx + ".obj";
        System.err.println(className +
                           ": outputting chart to Java object file " +
                           "\"" + chartFilename + "\"");
	BufferedOutputStream bos =
	  new BufferedOutputStream(new FileOutputStream(chartFilename),
				   Constants.defaultFileBufsize);
	ObjectOutputStream os = new ObjectOutputStream(bos);
        os.writeObject(chart);
        os.writeObject(topRankedItem);
        os.writeObject(sentence);
	os.writeObject(originalWords);
        os.close();
      }
      catch (IOException ioe) {
        System.err.println(ioe);
      }
    }

    chart.postParseCleanup();

    if (topRankedItem == null) {
      sentence.clear();
      sentence.addAll(originalSentence); // restore original sentence
      return null;
    }
    else {
      Sexp tree = topRankedItem.headChild().toSexp();
      postProcess(tree);
      return tree;
    }
  }

  protected void addTopUnaries(int end) throws RemoteException {
    topProbItemsToAdd.clear();
    Iterator sentSpanItems = chart.get(0, end);
    while (sentSpanItems.hasNext()) {
      CKYItem item = (CKYItem)sentSpanItems.next();
      if (item.stop()) {

        HeadEvent headEvent = lookupHeadEvent;
        headEvent.set(item.headWord(), topSym, (Symbol)item.label(),
		      emptySubcat, emptySubcat);
        double topLogProb = server.logProbTop(id, headEvent);
        double logProb = item.logTreeProb() + topLogProb;

        if (debugTop)
          System.err.println(className +
                             ": item=" + item + "; topLogProb=" + topLogProb +
                             "; item.logTreeProb()=" + item.logTreeProb() +
                             "; logProb=" + logProb);

        if (topLogProb <= Constants.logOfZero)
          continue;
        CKYItem newItem = chart.getNewItem();
        newItem.set(topSym, item.headWord(),
                    emptySubcat, emptySubcat, item,
                    null, null, startList, startList, 0, end,
                    false, false, true, logProb, 0.0, logProb);
        topProbItemsToAdd.add(newItem);
      }
    }
    Iterator toAdd = topProbItemsToAdd.iterator();
    while (toAdd.hasNext())
      chart.add(0, end, (CKYItem)toAdd.next());
  }

  protected void complete(int start, int end) throws RemoteException {
    for (int split = start; split < end; split++) {

      if (useCommaConstraint && commaConstraintViolation(start, split, end)) {
	if (debugCommaConstraint) {
	  System.err.println(className +
                             ": constraint violation at (start,split,end+1)=(" +
			     start + "," + split + "," + (end + 1) +
			     "); word at end+1 = " + getSentenceWord(end + 1));
	}
	// EVEN IF there is a constraint violation, we still try to find
	// modificands that have not yet received their stop probabilities
	// whose labels are baseNP, to see if we can add a premodifier
	// (so that we can build baseNPs to the left even if they contain
	// commas)
	boolean modifierSide = Constants.LEFT;
	int modificandStartIdx =  split + 1;
	int modificandEndIdx =    end;
	int modifierStartIdx  =   start;
	int modifierEndIdx =      split;
        if (debugComplete && debugSpans)
          System.err.println(className + ": modifying [" +
                             modificandStartIdx + "," + modificandEndIdx +
                             "]" + " with [" + modifierStartIdx + "," +
                             modifierEndIdx + "]");

        // for each possible modifier that HAS received its stop probabilities,
        // try to find a modificand that has NOT received its stop probabilities
        if (chart.numItems(modifierStartIdx, modifierEndIdx) > 0 &&
            chart.numItems(modificandStartIdx, modificandEndIdx) > 0) {
          Iterator modifierItems = chart.get(modifierStartIdx, modifierEndIdx);
          while (modifierItems.hasNext()) {
            CKYItem modifierItem = (CKYItem)modifierItems.next();
            if (modifierItem.stop()) {
              Iterator modificandItems =
                chart.get(modificandStartIdx, modificandEndIdx);
              while (modificandItems.hasNext()) {
                CKYItem modificandItem = (CKYItem)modificandItems.next();
                if (!modificandItem.stop() && modificandItem.label()==baseNP) {
		  if (debugComplete)
		    System.err.println(className +
				       ".complete: trying to modify\n\t" +
				       modificandItem + "\n\twith\n\t" +
				       modifierItem);
                  joinItems(modificandItem, modifierItem, modifierSide);
		}
              }
            }
          }
        }
	continue;
      }

      boolean modifierSide;
      for (int sideIdx = 0; sideIdx < 2; sideIdx++) {
        modifierSide = sideIdx == 0 ? Constants.RIGHT : Constants.LEFT;
        boolean modifyLeft = modifierSide == Constants.LEFT;

        int modificandStartIdx = modifyLeft ?  split + 1  :  start;
        int modificandEndIdx =   modifyLeft ?  end        :  split;

        int modifierStartIdx =   modifyLeft ?  start      :  split + 1;
        int modifierEndIdx =     modifyLeft ?  split      :  end;

        if (debugComplete && debugSpans)
          System.err.println(className + ": modifying [" +
                             modificandStartIdx + "," + modificandEndIdx +
                             "]" + " with [" + modifierStartIdx + "," +
                             modifierEndIdx + "]");

        // for each possible modifier that HAS received its stop probabilities,
        // try to find a modificand that has NOT received its stop probabilities
        if (chart.numItems(modifierStartIdx, modifierEndIdx) > 0 &&
            chart.numItems(modificandStartIdx, modificandEndIdx) > 0) {
          Iterator modifierItems = chart.get(modifierStartIdx, modifierEndIdx);
          while (modifierItems.hasNext()) {
            CKYItem modifierItem = (CKYItem)modifierItems.next();
            if (modifierItem.stop()) {
              Iterator modificandItems =
                chart.get(modificandStartIdx, modificandEndIdx);
              while (modificandItems.hasNext()) {
                CKYItem modificandItem = (CKYItem)modificandItems.next();
                if (!modificandItem.stop() &&
		    derivationOrderOK(modificandItem, modifierSide)) {
		/*
		if (!modificandItem.stop()) {
		*/
		  if (debugComplete)
		    System.err.println(className +
				       ".complete: trying to modify\n\t" +
				       modificandItem + "\n\twith\n\t" +
				       modifierItem);
                  joinItems(modificandItem, modifierItem, modifierSide);
		}
              }
            }
          }
        }
      }
    }
    addUnariesAndStopProbs(start, end);
    chart.prune(start, end);
  }

  /**
   * Enforces that modificand receives all its right modifiers before receiving
   * any left modifiers, by ensuring that right-modification only happens
   * when a modificand has no left-children (this is both necessary and
   * sufficient to enforce derivation order).  Also, in the case of
   * left-modification, this method checks to make sure that the right subcat
   * is empty (necessary but <i>not</i> sufficient to enforce derivation order).
   * This method is called by {@link #complete(int,int)}.
   */
  protected boolean derivationOrderOK(CKYItem modificand, boolean modifySide) {
    return (modifySide == Constants.LEFT ?
	    modificand.rightSubcat().empty() :
	    modificand.leftChildren() == null);
  }

  /**
   * Joins two chart items, one representing the modificand that has not
   * yet received its stop probabilities, the other representing the modifier
   * that has received its stop probabilities.
   *
   * @param modificand the chart item representing a partially-completed
   * subtree, to be modified on <code>side</code> by <code>modifier</code>
   * @param modifier the chart item representing a completed subtree that
   * will be added as a modifier on <code>side</code> of
   * <code>modificand</code>'s subtree
   * @param side the side on which to attempt to add the specified modifier
   * to the specified modificand
   */
  protected void joinItems(CKYItem modificand, CKYItem modifier,
                           boolean side)
  throws RemoteException {
    Symbol modLabel = (Symbol)modifier.label();

    Subcat thisSideSubcat = (Subcat)modificand.subcat(side);
    Subcat oppositeSideSubcat = modificand.subcat(!side);
    boolean thisSideSubcatContainsMod = thisSideSubcat.contains(modLabel);
    if (!thisSideSubcatContainsMod &&
	Language.training.isArgumentFast(modLabel))
      return;

    if (isomorphicTreeConstraints) {
      if (modificand.getConstraint().isViolatedByChild(modifier)) {
        if (debugConstraints)
          System.err.println("constraint " + modificand.getConstraint() +
                             " violated by child item(" +
                            modifier.start() + "," + modifier.end() + "): " +
                            modifier);
        return;
      }
    }

    /*
    SexpList thisSidePrevMods = getPrevMods(modificand,
					    modificand.prevMods(side),
                                            modificand.children(side));
    */
    /*
    SexpList thisSidePrevMods = modificand.prevMods(side);
    */
    tmpChildrenList.set(modifier, modificand.children(side));

    SexpList thisSidePrevMods = getPrevMods(modificand, tmpChildrenList);
    SexpList oppositeSidePrevMods = modificand.prevMods(!side);

    WordList previousWords = getPrevModWords(modificand, tmpChildrenList);

    int thisSideEdgeIndex = modifier.edgeIndex(side);
    int oppositeSideEdgeIndex = modificand.edgeIndex(!side);

    boolean thisSideContainsVerb =
      modificand.verb(side) || modifier.containsVerb();
    boolean oppositeSideContainsVerb = modificand.verb(!side);

    ModifierEvent modEvent = lookupModEvent;
    modEvent.set(modifier.headWord(),
                 modificand.headWord(),
                 modLabel,
                 thisSidePrevMods,
                 previousWords,
                 (Symbol)modificand.label(),
                 modificand.headLabel(),
                 modificand.subcat(side),
                 modificand.verb(side),
                 side);

    boolean debugFlag = false;
    if (debugJoin) {
      Symbol modificandLabel = (Symbol)modificand.label();
      boolean modificandLabelP = modificandLabel == NPA;
      boolean modLabelP = modLabel == RRC;
      debugFlag = (modificandLabelP && modLabelP && side == Constants.RIGHT &&
		   ((modificand.start() == 0 && modificand.end() == 3 &&
		     modifier.start() == 4 && modifier.end() == 10)));
    }

    if (!futurePossible(modEvent, side, debugFlag))
      return;

    if (debugJoin) {
    }

    int lowerIndex = Math.min(thisSideEdgeIndex, oppositeSideEdgeIndex);
    int higherIndex = Math.max(thisSideEdgeIndex, oppositeSideEdgeIndex);

    double logModProb = server.logProbMod(id, modEvent);
    if (logModProb <= Constants.logOfZero)
      return;
    double logTreeProb =
      modificand.logTreeProb() + modifier.logTreeProb() + logModProb;

    double logPrior = modificand.logPrior();
    double logProb = logTreeProb + logPrior;

    if (debugJoin) {
      if (debugFlag) {
	System.err.println(className + ".join: trying to extend modificand\n" +
			   modificand + "\nwith modifier\n" + modifier);
      }
    }

    // if this side's subcat contains the the current modifier's label as one
    // of its requirements, make a copy of it and remove the requirement
    if (thisSideSubcatContainsMod) {
      thisSideSubcat = (Subcat)thisSideSubcat.copy();
      thisSideSubcat.remove(modLabel);
    }

    SLNode thisSideChildren = new SLNode(modifier, modificand.children(side));
    SLNode oppositeSideChildren = modificand.children(!side);

    CKYItem newItem = chart.getNewItem();
    newItem.set((Symbol)modificand.label(), modificand.headWord(),
                null, null, modificand.headChild(), null, null, null, null,
                lowerIndex, higherIndex, false, false, false,
		logTreeProb, logPrior, logProb);

    tmpChildrenList.set(null, thisSideChildren);
    SexpList thisSideNewPrevMods = getPrevMods(modificand, tmpChildrenList);

    newItem.setSideInfo(side,
                        thisSideSubcat, thisSideChildren,
                        thisSideNewPrevMods, thisSideEdgeIndex,
                        thisSideContainsVerb);
    newItem.setSideInfo(!side,
                        oppositeSideSubcat, oppositeSideChildren,
                        oppositeSidePrevMods, oppositeSideEdgeIndex,
                        oppositeSideContainsVerb);

    if (isomorphicTreeConstraints) {
      if (debugConstraints)
        System.err.println("assigning partially-satisfied constraint " +
                           modificand.getConstraint() + " to " + newItem);
      newItem.setConstraint(modificand.getConstraint());
    }

    boolean added = chart.add(lowerIndex, higherIndex, newItem);
    if (!added)
      chart.reclaimItem(newItem);

    if (debugJoin) {
    }
  }

  private boolean futurePossible(ModifierEvent modEvent, boolean side,
				 boolean debug) {
    ProbabilityStructure modPS = modNonterminalPS;
    int lastLevel = modNonterminalPSLastLevel;
    boolean onLeft = side == Constants.LEFT;
    Map modMap = (onLeft ? leftModNonterminalMap : rightModNonterminalMap);
    Event historyContext = modPS.getHistory(modEvent, lastLevel);
    Set possibleFutures = (Set)modMap.get(historyContext);
    if (possibleFutures != null) {
      Event currentFuture = modPS.getFuture(modEvent, lastLevel);
      if (possibleFutures.contains(currentFuture))
        return true;
    }
    else {
      //no possible futures for history context
    }

    if (debug) {
      Event currentFuture = modPS.getFuture(modEvent, lastLevel);
      if (possibleFutures == null)
	System.err.println(className + ".futurePossible: history context " +
			   historyContext + " not seen in training");
      else if (!possibleFutures.contains(currentFuture))
	System.err.println(className + ".futurePossible: future " +
			   currentFuture + " not found for history context " +
			   historyContext);
    }

    return false;
  }

  private Set possibleFutures(ModifierEvent modEvent, boolean side) {
    ProbabilityStructure modPS = modNonterminalPS;
    int lastLevel = modNonterminalPSLastLevel;
    boolean onLeft = side == Constants.LEFT;
    Map modMap = (onLeft ? leftModNonterminalMap : rightModNonterminalMap);
    Event historyContext = modPS.getHistory(modEvent, lastLevel);
    Set possibleFutures = (Set)modMap.get(historyContext);
    return possibleFutures;
  }

  protected void addUnariesAndStopProbs(int start, int end)
  throws RemoteException {
    prevItemsAdded.clear();
    currItemsAdded.clear();
    stopProbItemsToAdd.clear();

    Iterator it = chart.get(start, end);
    while (it.hasNext()) {
      CKYItem item = (CKYItem)it.next();
      if (item.stop() == false)
	stopProbItemsToAdd.add(item);
      else if (item.isPreterminal())
        prevItemsAdded.add(item);
    }

    if (stopProbItemsToAdd.size() > 0) {
      it = stopProbItemsToAdd.iterator();
      while (it.hasNext())
        addStopProbs((CKYItem)it.next(), prevItemsAdded);
    }

    int i = -1;
    //for (i = 0; i < 5 && prevItemsAdded.size() > 0; i++) {
    for (i = 0; prevItemsAdded.size() > 0; i++) {
      Iterator prevItems = prevItemsAdded.iterator();
      while (prevItems.hasNext()) {
        CKYItem item = (CKYItem)prevItems.next();
        if (!item.garbage())
          addUnaries(item, currItemsAdded);
      }

      exchangePrevAndCurrItems();
      currItemsAdded.clear();

      prevItems = prevItemsAdded.iterator();
      while (prevItems.hasNext()) {
        CKYItem item = (CKYItem)prevItems.next();
        if (!item.garbage())
          addStopProbs(item, currItemsAdded);
      }
      exchangePrevAndCurrItems();
      currItemsAdded.clear();
    }
    if (debugUnariesAndStopProbs) {
      System.err.println(className +
                         ": added unaries and stop probs " + i + " times");
    }
  }

  private final void exchangePrevAndCurrItems() {
    List exchange;
    exchange = prevItemsAdded;
    prevItemsAdded = currItemsAdded;
    currItemsAdded = exchange;
  }

  protected List addUnaries(CKYItem item, List itemsAdded)
  throws RemoteException {
    unaryItemsToAdd.clear();
    CKYItem newItem = chart.getNewItem();
    newItem.set(null, item.headWord(), null, null, item,
                null, null, startList, startList,
                item.start(), item.end(),
                false, false, false, 0.0, 0.0, 0.0);
    Symbol headSym = (Symbol)item.label();
    HeadEvent headEvent = lookupHeadEvent;
    headEvent.set(item.headWord(), null, headSym, emptySubcat, emptySubcat);
    PriorEvent priorEvent = lookupPriorEvent;
    priorEvent.set(item.headWord(), null);
    // foreach nonterminal
    for (int ntIndex = 0; ntIndex < nonterminals.length; ntIndex++) {
      Symbol parent = nonterminals[ntIndex];
      headEvent.setParent(parent);
      Set leftSubcats = getPossibleSubcats(leftSubcatMap, headEvent,
                                           leftSubcatPS,
                                           leftSubcatPSLastLevel);
      Set rightSubcats = getPossibleSubcats(rightSubcatMap, headEvent,
                                            rightSubcatPS,
                                            rightSubcatPSLastLevel);
      if (debugUnaries) {
      }

      int numLeftSubcats = leftSubcats.size();
      int numRightSubcats = rightSubcats.size();
      if (numLeftSubcats > 0 && numRightSubcats > 0) {
        Iterator leftSubcatIt = leftSubcats.iterator();
        // foreach possible left subcat
        while (leftSubcatIt.hasNext()) {
          Subcat leftSubcat = (Subcat)leftSubcatIt.next();
          Iterator rightSubcatIt = rightSubcats.iterator();
          // foreach possible right subcat
          while (rightSubcatIt.hasNext()) {
            Subcat rightSubcat = (Subcat)rightSubcatIt.next();

            newItem.setLabel(parent);
            newItem.setLeftSubcat(leftSubcat);
            newItem.setRightSubcat(rightSubcat);

            headEvent.setLeftSubcat(leftSubcat);
            headEvent.setRightSubcat(rightSubcat);

            if (debugUnaries) {
            }

            if (isomorphicTreeConstraints) {
              // get head child's constraint's parent and check that it is
              // locally satisfied by newItem
              if (item.getConstraint() == null) {
                System.err.println("uh-oh: no constraint for item " + item);
              }
              Constraint headChildParent = item.getConstraint().getParent();
              if (headChildParent != null &&
                  headChildParent.isLocallySatisfiedBy(newItem)) {
                if (debugConstraints)
                  System.err.println("assigning locally-satisfied constraint " +
                                     headChildParent + " to " + newItem);
                newItem.setConstraint(headChildParent);
              }
              else {
                if (debugConstraints)
                  System.err.println("constraint " + headChildParent +
                                     " is not locally satisfied by item " +
                                     newItem);
                continue;
              }
            }
            else if (findAtLeastOneSatisfyingConstraint) {
              Constraint constraint = constraints.constraintSatisfying(newItem);
              if (constraint == null)
                continue;
              else
                newItem.setConstraint(constraint);
            }

	    double logProbLeftSubcat =
	      (numLeftSubcats == 1 ? 0.0 :
	       server.logProbLeftSubcat(id, headEvent));
	    double logProbRightSubcat =
	      (numRightSubcats == 1 ? 0.0 :
	       server.logProbRightSubcat(id, headEvent));
            double logProbHead = server.logProbHead(id, headEvent);
            if (logProbHead <= Constants.logOfZero)
              continue;
            double logTreeProb =
              item.logTreeProb() +
	      logProbHead + logProbLeftSubcat + logProbRightSubcat;

	    priorEvent.setLabel(parent);
            double logPrior = server.logPrior(id, priorEvent);

            if (logPrior <= Constants.logOfZero)
              continue;

            double logProb = logTreeProb + logPrior;

            if (debugUnaries) {
            }

            if (logProb <= Constants.logOfZero)
              continue;

            newItem.setLogTreeProb(logTreeProb);
            newItem.setLogPrior(logPrior);
            newItem.setLogProb(logProb);

            CKYItem newItemCopy = chart.getNewItem();
            newItemCopy.setDataFrom(newItem);
            unaryItemsToAdd.add(newItemCopy);
          }
        } // end foreach possible left subcat
      }
    }
    Iterator toAdd = unaryItemsToAdd.iterator();
    while (toAdd.hasNext()) {
      CKYItem itemToAdd = (CKYItem)toAdd.next();
      boolean added = chart.add(itemToAdd.start(), itemToAdd.end(), itemToAdd);
      if (added)
        itemsAdded.add(itemToAdd);
      else
        chart.reclaimItem(itemToAdd);
    }

    chart.reclaimItem(newItem);

    return itemsAdded;
  }

  private final Set getPossibleSubcats(Map subcatMap, HeadEvent headEvent,
                                       ProbabilityStructure subcatPS,
                                       int lastLevel) {
    Event lastLevelHist = subcatPS.getHistory(headEvent, lastLevel);
    Set subcats = (Set)subcatMap.get(lastLevelHist);
    return subcats == null ? Collections.EMPTY_SET : subcats;
  }

  protected List addStopProbs(CKYItem item, List itemsAdded)
    throws RemoteException {
    if (!(item.leftSubcat().empty() && item.rightSubcat().empty()))
      return itemsAdded;

    /*
    SexpList leftPrevMods =
      getPrevMods(item, item.leftPrevMods(), item.leftChildren());
    SexpList rightPrevMods =
      getPrevMods(item, item.rightPrevMods(), item.rightChildren());
    */

    // technically, we should getPrevMods for both lists here, but there
    // shouldn't be skipping of previous mods because of generation of stopSym
    SexpList leftPrevMods = item.leftPrevMods();
    SexpList rightPrevMods = item.rightPrevMods();

    tmpChildrenList.set(null, item.leftChildren());
    WordList leftPrevWords = getPrevModWords(item, tmpChildrenList);
    tmpChildrenList.set(null, item.rightChildren());
    WordList rightPrevWords = getPrevModWords(item, tmpChildrenList);

    ModifierEvent leftMod = lookupLeftStopEvent;
    leftMod.set(stopWord, item.headWord(), stopSym, leftPrevMods,
                leftPrevWords,
                (Symbol)item.label(), item.headLabel(), item.leftSubcat(),
                item.leftVerb(), Constants.LEFT);
    ModifierEvent rightMod = lookupRightStopEvent;
    rightMod.set(stopWord, item.headWord(), stopSym, rightPrevMods,
                 rightPrevWords,
                 (Symbol)item.label(), item.headLabel(),
                 item.rightSubcat(), item.rightVerb(), Constants.RIGHT);

    if (debugStops) {
    }

    if (isomorphicTreeConstraints) {
      if (!item.getConstraint().isSatisfiedBy(item)) {
        if (debugConstraints)
          System.err.println("constraint " + item.getConstraint() +
                             " is not satisfied by item " + item);
        return itemsAdded;
      }
    }

    double leftLogProb = server.logProbMod(id, leftMod);
    if (leftLogProb <= Constants.logOfZero)
      return itemsAdded;
    double rightLogProb = server.logProbMod(id, rightMod);
    if (rightLogProb <= Constants.logOfZero)
      return itemsAdded;
    double logTreeProb =
      item.logTreeProb() + leftLogProb + rightLogProb;

    double logPrior = item.logPrior();
    double logProb = logTreeProb + logPrior;

    if (debugStops) {
      if (item.start() == 0 && item.end() == 5 && item.label() == baseNP) {
	System.err.println(className + ".addStopProbs: adding stops to item " +
			   item);
      }
    }

    if (logProb <= Constants.logOfZero)
      return itemsAdded;

    CKYItem newItem = chart.getNewItem();
    newItem.set((Symbol)item.label(), item.headWord(),
                item.leftSubcat(), item.rightSubcat(),
                item.headChild(),
                item.leftChildren(), item.rightChildren(),
                item.leftPrevMods(), item.rightPrevMods(),
                item.start(), item.end(), item.leftVerb(),
                item.rightVerb(), true, logTreeProb, logPrior, logProb);

    if (isomorphicTreeConstraints) {
      if (debugConstraints)
        System.err.println("assigning satisfied constraint " +
                           item.getConstraint() + " to " + newItem);
      newItem.setConstraint(item.getConstraint());
    }

    boolean added = chart.add(item.start(), item.end(), newItem);
    if (added)
      itemsAdded.add(newItem);
    else
      chart.reclaimItem(newItem);

    return itemsAdded;
  }

  /**
   * Creates a new previous-modifier list given the specified current list
   * and the last modifier on a particular side.
   *
   * @param modChildren the last node of modifying children on a particular
   * side of the head of a chart item
   * @return the list whose first element is the label of the specified
   * modifying child and whose subsequent elements are those of the
   * specified <code>itemPrevMods</code> list, without its final element
   * (which is "bumped off" the edge, since the previous-modifier list
   * has a constant length)
   */
  private final SexpList getPrevMods(CKYItem item, SLNode modChildren) {
    if (modChildren == null)
      return startList;
    prevModLookupList.clear();
    SexpList prevMods = prevModLookupList;
    int i = 0; // index in prev mod list we are constructing
    // as long as there are children and we haven't reached the numPrevMods
    // limit, set elements of prevModList, starting at index 0
    for (SLNode curr = modChildren; curr != null && i < numPrevMods; ) {
      Symbol currMod = (curr.data() == null ? stopSym :
			(Symbol)((CKYItem)curr.data()).label());
      Symbol prevMod = (curr.next() == null ? startSym :
			(Symbol)((CKYItem)curr.next().data()).label());
      if (!Shifter.skip(item, prevMod, currMod)) {
	prevMods.add(prevMod);
	i++;
      }
      curr = curr.next();
    }

    // if, due to skipping, we haven't finished setting prevModList, finish here
    if (i == 0)
      return startList;
    for (; i < numPrevMods; i++)
      prevMods.add(startSym);

    SexpList canonical = (SexpList)canonicalPrevModLists.get(prevMods);
    if (canonical == null) {
      prevMods = (SexpList)prevMods.deepCopy();
      canonicalPrevModLists.put(prevMods, prevMods);
      canonical = prevMods;
    }
    return canonical;
  }

  private final WordList getPrevModWords(CKYItem item, SLNode modChildren) {
    if (modChildren == null)
      return startWordList;
    WordList wordList = prevModWordLookupList;
    int i = 0; // the index of the previous mod head wordlist
    // as long as there are children and we haven't reached the numPrevWords
    // limit, set elements of wordList, starting at index 0 (i = 0, initially)
    for (SLNode curr = modChildren; curr!=null && i < numPrevWords;) {
      Word currWord = (curr.data() == null ? stopWord :
		       ((CKYItem)curr.data()).headWord());
      Word prevWord = (curr.next() == null ? startWord :
		       (Word)((CKYItem)curr.next().data()).headWord());
      if (!Shifter.skip(item, prevWord, currWord))
	wordList.set(i++, prevWord);
      curr = curr.next();
    }
    // if we ran out of children, but haven't finished setting all numPrevWords
    // elements of word list, set remainder of word list with startWord
    if (i == 0)
      return startWordList;
    for ( ; i < numPrevWords; i++)
      wordList.set(i, startWord);

    return wordList;
  }

  private final Symbol getSentenceWord(int index) {
    return (index >= sentLen ? null :
	    (sentence.get(index).isSymbol() ? sentence.symbolAt(index) :
	     sentence.listAt(index).symbolAt(1)));

  }
}