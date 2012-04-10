/**
 * Copyright 2003-2007 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 *
 * This file is part of MARY TTS.
 *
 * MARY TTS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package marytts.language.sv;

import java.io.*;
import java.util.*;

import marytts.datatypes.MaryData;
import marytts.datatypes.MaryDataType;
import marytts.datatypes.MaryXML;
import marytts.exceptions.MaryConfigurationException;
import marytts.server.MaryProperties;
import marytts.util.MaryUtils;
import marytts.util.dom.MaryDomUtils;
import marytts.modules.phonemiser.TrainedLTS;
import marytts.language.sv.phonemiser.SV_Syllabifier;
import marytts.modules.phonemiser.AllophoneSet;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.traversal.NodeIterator;
import org.w3c.dom.Element;

/**
 * The phonemiser module, tweaked so as to allow for using English lexicon and LTS-rules for 
 * English words. An open source language detection software by Nakatani Shuyo is used to 
 * determine language on words not in either Swedish nor English dictionary. LTS-rules 
 * corresponding to the detected language are then used to generate a pronunciation. 
 * 
 * @author Erik Margaronis, Erik Sterneberg, Harald Berthelsen
 *
 */
public class JPhonemiser extends marytts.modules.JPhonemiser {
	private String basePath;
	private String logUnknownFileName = null;
	private Map<String,Integer> unknown2Frequency = null;
	private String logEnglishFileName = null;
	private Map<String,Integer> english2Frequency = null;

	// Treating a word as a compound, this class tries to split it up into words found in the dictionary
	private sv_CompoundSplitter cs;    
	// This class is used to get the pronunciation for English words.
	private svPhonemizer_engLexicon en_phonemizer;
	// Used to map phonemes in English lexicon to the Swedish allophone set 
	private HashMap<String, String> engToSwePhonemes;
	// Language detection class
	private LI li;

	public JPhonemiser() throws IOException, MaryConfigurationException {
		super("JPhonemiser_sv",
				MaryDataType.PARTSOFSPEECH,
				MaryDataType.PHONEMES,
				MaryProperties.needFilename("sv.allophoneset"),
				MaryProperties.getFilename("sv.userdict"),
				MaryProperties.needFilename("sv.lexicon"),
				MaryProperties.needFilename("sv.lettertosound"));
	}


    public JPhonemiser(String propertyPrefix)
    throws IOException,  MaryConfigurationException
    {
        super("JPhonemiser", MaryDataType.PARTSOFSPEECH, MaryDataType.PHONEMES,
        		propertyPrefix+"allophoneset",
                propertyPrefix+"userdict",
                propertyPrefix+"lexicon",
                propertyPrefix+"lettertosound");
    }
    


	public void startup() throws Exception {
		super.startup();

		// Initialize compound splitter		
		this.cs = new sv_CompoundSplitter(); 

		// Initialize "help" phonemizer class which contains en_GB lexicon and lts-rules
		this.en_phonemizer = new svPhonemizer_engLexicon();

		// Map English allophones to Swedish allophones
		
		//System.out.println("Populating 'engToSwePhonemes' map:");
		this.engToSwePhonemes = generateMap();		

		basePath = MaryProperties.maryBase() + File.separator +
		"lib" + File.separator + "modules" + File.separator + "sv" + File.separator +
		"lexicon" + File.separator;
		if (MaryProperties.getBoolean("sv.phonemizer.logunknown")) {
			String logBasepath = MaryProperties.maryBase() + File.separator +
			"log" + File.separator;
			File logDir = new File(logBasepath);
			try {
				if (!logDir.isDirectory()) {
					logger.info("Creating log directory " + logDir.getCanonicalPath());
					FileUtils.forceMkdir(logDir);
				}
				logUnknownFileName = MaryProperties.getFilename("sv.phonemizer.logunknown.filename",logBasepath+"sv_unknown.txt");
				// more startup procedures?
				// for example log files?
			} catch (IOException e) {
				logger.info("Could not create log directory " + logDir.getCanonicalPath() + " Logging disabled!", e);
			}
		}
	}

	public void shutdown() {
		if (logUnknownFileName != null || logEnglishFileName != null) {
			try {
				// Print unknown words
				// open file
				PrintWriter logUnknown = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logUnknownFileName), "UTF-8"));
				// sort the words
				Set<String> unknownWords = unknown2Frequency.keySet();
				SortedMap<Integer,List<String>> freq2Unknown = new TreeMap<Integer, List<String>>();
				for (String nextUnknown : unknownWords) {
					int nextFreq = unknown2Frequency.get(nextUnknown);  
					if (freq2Unknown.containsKey(nextFreq)){
						List<String> unknowns = freq2Unknown.get(nextFreq);
						unknowns.add(nextUnknown);
					} else {
						List<String> unknowns = new ArrayList<String>();
						unknowns.add(nextUnknown);
						freq2Unknown.put(nextFreq,unknowns);
					}
				}
				//print the words
				for (int nextFreq : freq2Unknown.keySet()) {
					List<String> unknowns = freq2Unknown.get(nextFreq);
					for (int i=0;i<unknowns.size();i++){
						String unknownWord = (String) unknowns.get(i);                    
						logUnknown.println(nextFreq+" "+unknownWord);
					}

				}
				//close file
				logUnknown.flush();
				logUnknown.close();

				PrintWriter logEnglish = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logEnglishFileName), "UTF-8"));
				//sort the words
				SortedMap<Integer,List<String>> freq2English = new TreeMap<Integer, List<String>>();
				for (String nextEnglish : english2Frequency.keySet()){                
					int nextFreq = english2Frequency.get(nextEnglish);
					if (freq2English.containsKey(nextFreq)) {
						List<String> englishWords = freq2English.get(nextFreq);
						englishWords.add(nextEnglish);
					} else {
						List<String> englishWords = new ArrayList<String>();
						englishWords.add(nextEnglish);
						freq2English.put(nextFreq,englishWords);
					}    

				}
				//print the words
				for (int nextFreq : freq2English.keySet()){
					List<String> englishWords = freq2English.get(nextFreq);
					for (int i=0;i<englishWords.size();i++){
						logEnglish.println(nextFreq+" "+englishWords.get(i));
					}
				}
				//close file
				logEnglish.flush();
				logEnglish.close();
				//implement shutdown procedure

			} catch (Exception e){
				logger.info("Error printing log files for english and unknown words", e);
			}
		}
	}

	@Override
	public MaryData process(MaryData d) throws Exception {
		// Initialize an LI object for logging the language detection task
		this.li = new LI();

		Document doc = d.getDocument();
		NodeIterator it = MaryDomUtils.createNodeIterator(doc, doc, MaryXML.TOKEN);
		Element t = null;
		while ((t = (Element) it.nextNode()) != null) {
			String text;
			// Do not touch tokens for which a transcription is already
			// given (exception: transcription contains a '*' character:
			if (t.hasAttribute("ph") &&
					t.getAttribute("ph").indexOf('*') == -1) {
				continue;
			}

			if (t.hasAttribute("sounds_like"))
				text = t.getAttribute("sounds_like");
			else {
				text = MaryDomUtils.tokenText(t);
			}

			// Phonemize letter sequence
			if (t.hasAttribute("say-as")) {
				if (t.getAttribute("say-as").equals("LSEQ")) {
					text = MaryDomUtils.tokenText(t);
					String tmp = "";
					for (int i=0; i< text.length(); i++) {
						tmp = tmp + text.substring(i,i+1) + "-";
					}
					text = tmp.substring(0, tmp.length()-1);
				}
			}

			String pos = null;
			// Use part-of-speech if available
			if (t.hasAttribute("pos")){
				pos = t.getAttribute("pos");
			}

			boolean isEnglish = false;
			if (t.hasAttribute("xml:lang") && MaryUtils.subsumes(Locale.ENGLISH, MaryUtils.string2locale(t.getAttribute("xml:lang")))) {
				isEnglish = true;
			}

			if (text != null && !text.equals("")) {
				// If text consists of several parts (e.g., because that was
				// inserted into the sounds_like attribute), each part
				// is transcribed
				StringBuilder ph = new StringBuilder();
				String g2pMethod = null;
				StringTokenizer st = new StringTokenizer(text, " -");
				while (st.hasMoreTokens()) {
					String graph = st.nextToken();
					StringBuilder helper = new StringBuilder();
					String phon = null;

					// Punctuation has no pronunciation and so need not be fed to the phonemizer 
					if (graph.matches("[\\.,:;!\\?]"))
						continue;

					if (phon == null) {
						phon = phonemise(graph, pos, helper);
					}
					if (ph.length() == 0) { // first part
						// The g2pMethod of the combined best is
						// the g2pMethod of the first constituent.
						g2pMethod = helper.toString();
						ph.append(phon);
					} else { // following parts
						ph.append(" - ");
						ph.append(phon);
					}
				}

				if (ph != null && ph.length() > 0) {
					setPh(t, ph.toString());
					t.setAttribute("g2p_method", g2pMethod);
				}
			}
		}
		MaryData result = new MaryData(outputType(), d.getLocale());
		result.setDocument(doc);
		return result;
	}

	@Override
	/**
	 * 	Phonemizes a word. Will try in order:
	 *  1. Look up in Swedish user dictionary, than in the Swedish lexicon
	 *  2. Try to split as a Swedish compound
	 *  3. Try to look up the string normalized to Swedish locale, repeating above steps.
	 *  4. Look up in English dictionary
	 *  5. Use LI (Language Idenfication) to determine which language it is (only Swedish and English), 
	 *  using the corresponding LTS-rules. If a word is determined to be English but the threshold value
	 *  of 99 % has not been exceeded, the language defaults to Swedish.
	 *  
	 */
	public String phonemise(String text, String pos, StringBuilder g2pMethod) {    	
		// 1a
		String result = userdictLookup(text, pos);
		if (result != null) {
			g2pMethod.append("userdict");
			return result;
		}
		// 1b
		result = lexiconLookup(text, pos);
		if (result != null) {
			g2pMethod.append("lexicon");
			return result;
		}
		/*
		else{
			System.out.println("The word " + text + " was not found in the Swedish dictionary.");
		}
		*/

		// 2. Compound analysis
		if (text.matches(".*[\\w].*")){
			result = compoundLookup(text, pos);
			if (result != null){
				g2pMethod.append("compound");
				return result;
			}
			/*
			else{
				System.out.println("The word " + text + " was not recognized as a compound.");
			}
			*/
		}

		// Normalize exotic letters (diacritics on vowels etc.)
		String normalized = MaryUtils.normaliseUnicodeLetters(text, Locale.GERMAN);
		if (!normalized.equals(text)) {
			// First, try a simple userdict and lexicon lookup:
			result = userdictLookup(normalized, pos);
			if (result != null) {
				g2pMethod.append("userdict");
				return result;
			}
			result = lexiconLookup(normalized, pos);
			if (result != null) {
				g2pMethod.append("lexicon");
				return result;
			}
		}

		// 4. Look up in English dictionary if the word does not contain any of the letters ÅÄÖåäö and is not all uppercase
		if (! text.matches("^.*[ÅÄÖåäö].*$") && text.matches(".*[a-z].*")){        
			//result = en_phonemizer.phonemise_UseDicts(text, pos, g2pMethod);
			result = en_phonemizer.phonemise(text, pos, g2pMethod);
			if (result != null){
				// must post-process the pronunciation, convert to Swedish voice compatible format
//				System.out.println("The word " + text + " was recognized as an English word.");

				return engSwePhonemeConversion(result);        	
			}        
			/*
			else{
				System.out.println("The word " + text + " was not recognized as an English word.");
			}
			*/
		}


		// 5. Use LI (if there are even letters in the string) and then either Swedish or English LTS-rules
		// If LI fails, language defaults to Swedish
		String lang = null;
		if (text.matches(".*[\\w].*")){
			if (text.matches("^[A-ZÅÄÖ]+$")){ // Never read all upper case words as English
				lang = "sv";
			}
			else{
				//System.out.println("The word was not all upper case.");
				try{
					lang = LI.detectLang(text);
					if(lang.equals("en"))
						li.log("The string '" + text + "' was identified as being an English word.");
					else
						li.log("The string '" + text + "' was identified as being a Swedish word.");
				}
				catch(Exception e){
					System.err.println("Error at detecting language: " + e.getMessage());
					lang = "sv";
				}
			}
		}
		else{
			lang = "sv";
		}

		if (lang.equals("sv")) {
			// Swedish Letter to sound rules if the language is Swedish
			result =  ltsLookup(text);
			if (result != null){
				g2pMethod.append("Swedish lts rules");
				return result;
			}
		}
		else{    	
			//English letter to sound rules if the language is English
			result = en_phonemizer.ltsLookup(text);
			if (result != null){
				g2pMethod.append("English lts rules");
				return engSwePhonemeConversion(result);        		
			}
		}

		//System.out.println("No pronunciation was found");
		return null;
	}

	
	/**
	 * Simple method that populates as hashmap with English phonemes mapped to Swedish phonemes.
	 * 
	 * @return HashMap
	 */
	private HashMap<String, String> generateMap() {
		HashMap<String, String> map = new HashMap<String, String>();
		try{
			BufferedReader infile = new BufferedReader(new FileReader(MaryProperties.needFilename("sv_SE.engToSwePhonemeMap")));
			String s;
			while ((s = infile.readLine()) != null){
				if (s.length() > 0){
					if (s.charAt(0) != '#'){
						String[] phones = s.trim().split("\t");			
						map.put(phones[0], phones[1]);						
					}
				}
			}
		}
		catch (Exception e){
			System.err.println("Error: " + e.getMessage());
		}
		return map;
	}

	/**
	 * English allophones not in the Swedish allophone set must be mapped to (approximate sounding) equivalents in Swedish allophone set.
	 * 
	 * @param text
	 */
	private String engSwePhonemeConversion(String text){
		for (String key: engToSwePhonemes.keySet())		
			text = text.replaceAll("(^|[ ])" + key +"($|[ ])", " " + engToSwePhonemes.get(key) + " ");		
		return text;
	}

	/**
	 * Corrects stress markers. Errors are likely to occur when a pronunciation has been generated by
	 * the compoundsplitter or the LTS-rules. 
	 * 
	 * @param phonemes
	 * @return
	 */
	public static String correctStressMarkers(String phonemes) {
		String[] phones = phonemes.split("\\s+");
		int marker=100;
		boolean multipleMarkers=false;
		for (int i=0; i<phones.length; i++) {

			// Look for first stressmarker
			if (phones[i].startsWith("'") | phones[i].startsWith(",") | phones[i].startsWith("%")) {
				// Remove all following markers and make sure the last one is "%"
				for (int j=i+1; j<phones.length; j++) {
					if (phones[j].startsWith("'") | phones[j].startsWith(",") | phones[j].startsWith("%")) {
						multipleMarkers=true;
						// Change first marker to "," for words with more than one markers
						phones[i] = ","+phones[i].substring(1,phones[i].length());

						if (j>marker) {
							// Remove previous marker if there are more
							phones[marker] = ""+phones[marker].substring(1,phones[marker].length());
						}
						marker=j;
					}
				}
				if (multipleMarkers) {
					// Change last marker to "%"
					phones[marker] = "%"+phones[marker].substring(1,phones[marker].length());
					break;
				} else {
					phones[i] = "'"+phones[i].substring(1,phones[i].length());
				}
			}
		}
		//phonemes = StringUtils.join(phones, " ");
		return StringUtils.join(phones, " ");
	}

	/**
	 * Used to generate a pronunciation for Swedish words OOV (Out Of Vocabulary) using Letter To Sound-rules (LTS).
	 * 
	 * @param text
	 * @return
	 */
	protected String ltsLookup(String text){
	    String phones = lts.predictPronunciation(text);
	    String result = null;
	    //Svensk syllabifier
	    try {
		AllophoneSet allSet = AllophoneSet.getAllophoneSet(MaryProperties.needFilename("sv_SE.allophoneset"));
		//SV_Syllabifier syl = new SV_Syllabifier(allSet, allSet.getStressMarkers());
		//sv.stressmarkers = \' , %
		SV_Syllabifier syl = new SV_Syllabifier(allSet, "',%-");
		result = syl.syllabify(phones);
	    } 
	    catch (Exception e) {
		e.printStackTrace();
	    }
	    
	    if (result != null) {
		if (logUnknownFileName != null) {
		    String unknownText = text.trim();
		    if (unknown2Frequency.containsKey(unknownText)){
			int textFreq = unknown2Frequency.get(unknownText);
			textFreq++;
			unknown2Frequency.put(unknownText, textFreq);
		    } 
		    else {
			unknown2Frequency.put(unknownText, new Integer(1));
		    }
		}
		
		result = correctStressMarkers(result);
		return result;
	    }
	    return null;            
	}
    

	/**
	 * Tries to split a word into parts found in the lexicon.
	 * 
	 * @param text
	 * @param pos
	 * @return String 
	 */
	protected String compoundLookup(String text, String pos){
		StringBuilder text_phonemized = new StringBuilder();

		// Split word into compounds	
		ArrayList<String[]> bestSplit = cs.splitCompound(text.toLowerCase(new Locale("sv_SE")));
		// Example: 
		// the arraylist for the string "Tysklandssemester" will look like this: (["Tyskland", "LEX"], ["s", "INFL"], ["semester", "LEX"]),
		// "()" being the arraylist and "[]" being a string vector

		// Run userdictLookup and/or lexiconLookup on the parts marked "LEX" and lts.predictPronunciation() on the parts marked "INFL"     	
		for (int i=0; i < bestSplit.size(); i++){
			
			// Get pronunciation for part
			String ph = null;
			if (bestSplit.get(i)[1].equals("LEX")){    			
				// Get pronunciation with userdictLookup and/or lexiconLookup
				// First check userdictLookup
				ph = userdictLookup(bestSplit.get(i)[0], pos);
				if (ph == null){
					ph = lexiconLookup(bestSplit.get(i)[0], pos);
				}
				//System.out.println("After userdict/lexicon lookup, the text " + bestSplit.get(i)[0] + " got the phonemes:" + ph);
			}    		

			// If the part was an inflection, get the pronunciation from there, if any
			else if (cs.inflection_endings.containsKey(bestSplit.get(i)[0])
					&& ! cs.inflection_endings.get(bestSplit.get(i)[0]).equals(""))
				ph = cs.inflection_endings.get(bestSplit.get(i)[0]);

			else {
				// Get pronunciation with ltsLookup. 
				ph = ltsLookup(bestSplit.get(i)[0]);
				//System.out.println("After lts, the text " + bestSplit.get(i)[0] + " got the phonemes:" + ph);
			}

			if (ph != null){
				if (i > 0) text_phonemized.append(" - "); // delimiter used for syllables
				text_phonemized.append(ph);
			}
		}         
		//System.out.println("Method compoundLookup returning string: " + text_phonemized.toString());
		if (text_phonemized.length() > 0) {
			String returnText = correctStressMarkers(text_phonemized.toString());
			return returnText;
		} else {
			return null;
		}   
	}    
}