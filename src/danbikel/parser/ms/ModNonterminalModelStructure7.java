package danbikel.parser.ms;

import danbikel.parser.*;
import danbikel.lisp.*;

public class ModNonterminalModelStructure7 extends ProbabilityStructure {
  // data members
  private static Symbol startSym = Language.training().startSym();
  private static Word startWord = Language.training().startWord();
  private Symbol topSym = Language.training().topSym();
  private ProbabilityStructure[] jointStructures = {new TagModelStructure2()};

  public ModNonterminalModelStructure7() {
    super();
  }

  public int maxEventComponents() { return 8; }
  public int numLevels() { return 2; }

  public Event getHistory(TrainerEvent trainerEvent, int backOffLevel) {
    ModifierEvent modEvent = (ModifierEvent)trainerEvent;

    if (Language.treebank().isBaseNP(modEvent.parent()))
      return getBaseNPHistory(modEvent, backOffLevel);

    Symbol side = Constants.sideToSym(modEvent.side());

    MutableEvent hist = historiesWithSubcats[backOffLevel];

    hist.clear();
    Symbol verbInterveningSym =
      Constants.booleanToSym(modEvent.verbIntervening());
    Symbol mappedPrevModSym =
      NTMapper.map(modEvent.previousMods().symbolAt(0));
    Symbol parent =
      Language.training().removeArgAugmentation(modEvent.parent());

    switch (backOffLevel) {
      /*
    case 0:
      // for p(M_i | P, H, w, t, verbIntervening, map(M_i-1), subcat, side)
      hist.add(0, parent);
      hist.add(0, Language.training().removeGapAugmentation(modEvent.head()));
      hist.add(0, modEvent.headWord().word());
      hist.add(0, modEvent.headWord().tag());
      hist.add(0, verbInterveningSym);
      hist.add(0, mappedPrevModSym);
      hist.add(1, modEvent.subcat());
      hist.add(0, side);
      break;
      */
    case 0:
      // for p(M_i | P, H, t, verbIntervening, map(M_i-1), subcat, side)
      hist.add(0, parent);
      hist.add(0, Language.training().removeGapAugmentation(modEvent.head()));
      hist.add(0, modEvent.headWord().tag());
      hist.add(0, verbInterveningSym);
      hist.add(0, mappedPrevModSym);
      hist.add(1, modEvent.subcat());
      hist.add(0, side);
      break;
    case 1:
      // for p(M_i | P, H, verbIntervening, map(M_i-1), subcat, side)
      hist.add(0, parent);
      hist.add(0, Language.training().removeGapAugmentation(modEvent.head()));
      hist.add(0, verbInterveningSym);
      hist.add(0, mappedPrevModSym);
      hist.add(1, modEvent.subcat());
      hist.add(0, side);
      break;
      /*
    case 3:
      // for p(M_i | P, H, subcat)
      hist.add(0, parent);
      hist.add(0, Language.training().removeGapAugmentation(modEvent.head()));
      hist.add(1, modEvent.subcat());
      break;
      */
    }
    return hist;
  }

  private Event getBaseNPHistory(ModifierEvent modEvent, int backOffLevel) {
    MutableEvent hist = histories[backOffLevel];

    Symbol side = Constants.sideToSym(modEvent.side());

    Symbol prevModLabel =
      (modEvent.previousMods().get(0) == startSym ?
       modEvent.head() : modEvent.previousMods().symbolAt(0));
    Word prevModWord =
      (modEvent.previousWords().getWord(0).equals(startWord) ?
       modEvent.headWord() : modEvent.previousWords().getWord(0));
    hist.clear();
    switch (backOffLevel) {
      /*
    case 0:
      // for p(M_i | P, M(w,t)_i-1, side)
      hist.add(Language.training().removeGapAugmentation(modEvent.parent()));
      hist.add(prevModLabel);
      hist.add(prevModWord.word());
      hist.add(prevModWord.tag());
      hist.add(side);
      break;
      */
    case 0:
      // for p(M_i | P, M(t)_i-1, side)
      hist.add(Language.training().removeGapAugmentation(modEvent.parent()));
      hist.add(prevModLabel);
      hist.add(prevModWord.tag());
      hist.add(side);
      break;
    case 1:
      // for p(M_i | P, M_i-1, side)
      hist.add(Language.training().removeGapAugmentation(modEvent.parent()));
      hist.add(prevModLabel);
      hist.add(side);
      break;
      /*
    case 3:
      // for p(M_i | P, side)
      hist.add(Language.training().removeGapAugmentation(modEvent.parent()));
      hist.add(side);
      break;
      */
    }
    return hist;
  }

  public Event getFuture(TrainerEvent trainerEvent, int backOffLevel) {
    ModifierEvent modEvent = (ModifierEvent)trainerEvent;
    MutableEvent future = futures[backOffLevel];
    future.clear();
    future.add(modEvent.modifier());
    return future;
  }

  public Model newModel() { return new JointModel(this); }

  public ProbabilityStructure[] jointModel() {
    return jointStructures;
  }

  public boolean doCleanup() { return true; }

  /**
   * In order to gather statistics for words that appear as the head of
   * the entire sentence when estimating p(w | t), the trainer "fakes" a
   * modifier event, as though the root node of the observed tree was seen
   * to modify the magical +TOP+ node.  We will never use the derived counts
   * whose history contexts contain +TOP+.  This method allows for the
   * removal of these "unnecessary" counts, which will never be used
   * when decoding.
   */
  public boolean removeHistory(int backOffLevel, Event history) {
    // this method assumes the parent component of histories for
    // all back-off levels will be at index 0.  IF THIS CHANGES,
    // this method will need to change accordingly.
    return history.get(0, 0) == topSym;
  }

  public ProbabilityStructure copy() {
    return new ModNonterminalModelStructure7();
  }
}
