package org.grobid.core.document;

import com.google.common.collect.Sets;
import nu.xom.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.grobid.core.GrobidModels;
import org.grobid.core.data.*;
import org.grobid.core.data.table.Cell;
import org.grobid.core.data.table.Line;
import org.grobid.core.data.table.LinePart;
import org.grobid.core.data.table.Row;
import org.grobid.core.document.xml.XmlBuilderUtils;
import org.grobid.core.engines.Engine;
import org.grobid.core.engines.FullTextParser;
import org.grobid.core.engines.config.GrobidAnalysisConfig;
import org.grobid.core.engines.label.TaggingLabel;
import org.grobid.core.engines.label.TaggingLabels;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.lang.Language;
import org.grobid.core.layout.Block;
import org.grobid.core.layout.GraphicObject;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.layout.LayoutTokenization;
import org.grobid.core.tokenization.TaggingTokenCluster;
import org.grobid.core.tokenization.TaggingTokenClusteror;
import org.grobid.core.utilities.KeyGen;
import org.grobid.core.utilities.LanguageUtilities;
import org.grobid.core.utilities.LayoutTokensUtil;
import org.grobid.core.utilities.TextUtilities;
import org.grobid.core.utilities.matching.EntityMatcherException;
import org.grobid.core.utilities.matching.ReferenceMarkerMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Matcher;

import static org.grobid.core.document.xml.XmlBuilderUtils.*;

public class JATSFormatter {

	protected static final Logger LOGGER = LoggerFactory.getLogger(JATSFormatter.class);

	private Document doc;
	private FullTextParser fullTextParser;
	private static final int JATS_SECTION_TYPE_NONE = 0;
	private static final int JATS_SECTION_TYPE_SECTION = 1;
	private static final int JATS_SECTION_TYPE_SUBSECTION = 2;

	private static final String BIBLIO_TYPE_JOURNAL = "journal";
	private static final String BIBLIO_TYPE_BOOK = "book";
	private static final String BIBLIO_TYPE_CHAPTER = "chapter";
	private static final String BIBLIO_TYPE_CONFERENCE = "confproc";
	private static final String BIBLIO_TYPE_WEBPAGE = "webpage";
	private static final String BIBLIO_TYPE_ARTICLE = "article";

	public static final Set<TaggingLabel> MARKER_LABELS = Sets.newHashSet(
			TaggingLabels.CITATION_MARKER,
			TaggingLabels.FIGURE_MARKER,
			TaggingLabels.TABLE_MARKER,
			TaggingLabels.EQUATION_MARKER);

	public JATSFormatter(Document document, FullTextParser fullTextParser) {
		this.doc = document;
		this.fullTextParser = fullTextParser;
	}

	public StringBuilder toJATSHeader(BiblioItem biblio, List<BibDataSet> bds, GrobidAnalysisConfig config) {
		StringBuilder jats = new StringBuilder();
		jats.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		jats.append("<!DOCTYPE article PUBLIC \"-//NLM//DTD JATS (Z39.96) Journal Publishing DTD v1.2 20190208//EN\" \"https://jats.nlm.nih.gov/publishing/1.2/JATS-journalpublishing1.dtd\">");
		jats.append("\n<article dtd-version=\"1.2\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">\n");
		jats.append("\t<front>\n");

		if (biblio == null) {
			// if the biblio object is null, we simply create an empty one
			biblio = new BiblioItem();
		}

		// Journal metadata
		if (biblio.getPublisher() != null || (biblio.getJournal() != null || biblio.getJournalAbbrev() != null || biblio.getISSN() != null || biblio.getISSNe() != null)) {
			jats.append("\t\t<journal-meta>\n");

			if (biblio.getPublisher() != null) {
				jats.append("\t\t\t<publisher>\n");
				jats.append("\t\t\t\t<publisher-name>");
				TextUtilities.HTMLEncode(biblio.getPublisher());
				jats.append("</publisher-name>\n");
				jats.append("\t\t\t</publisher>\n");
			}

			if (biblio.getJournal() != null || biblio.getJournalAbbrev() != null) {
				jats.append("\t\t\t<journal-title-group>\n");
				if (biblio.getJournal() != null) {
					jats.append("\t\t\t\t<journal-title>");
					TextUtilities.HTMLEncode(biblio.getJournal());
					jats.append("\t\t\t</journal-title>\n");
				}
				if (biblio.getJournalAbbrev() != null) {
					jats.append("\t\t\t\t<abbrev-journal-title>");
					TextUtilities.HTMLEncode(biblio.getJournalAbbrev());
					jats.append("</abbrev-journal-title>\n");
				}
				jats.append("\t\t\t</journal-title-group>\n");
			}

			if (biblio.getISSN() != null) {
				jats.append("\t\t\t<issn publication-format=\"print\">");
				TextUtilities.HTMLEncode(biblio.getISSN());
				jats.append("</issn>\n");
			}

			if (biblio.getISSNe() != null) {
				jats.append("\t\t\t<issn publication-format=\"electronic\">");
				TextUtilities.HTMLEncode(biblio.getISSNe());
				jats.append("</issn>\n");
			}

			jats.append("\t\t</journal-meta>\n");
		}

		// Article metadata
		jats.append("\t\t<article-meta>\n");
		jats.append("\t\t\t<title-group>\n");
		jats.append("\t\t\t\t<article-title>");

		if (biblio.getTitle() != null) {
			jats.append(TextUtilities.HTMLEncode(biblio.getTitle()));
		}

		jats.append("</article-title>\n");
		jats.append("\t\t\t</title-group>\n");

		if (biblio.getFullAuthors() != null) {
			HashMap<String, Affiliation> affData = new LinkedHashMap<>();
			jats.append("\t\t\t<contrib-group content-type=\"author\">\n");
			for (Person author: biblio.getFullAuthors()) {
				jats.append("\t\t\t\t<contrib contrib-type=\"person\">\n");
				if (author.getFirstName() != null || author.getLastName() != null) {
					jats.append("\t\t\t\t\t<name>\n");
					if (author.getLastName() != null) {
						jats.append("\t\t\t\t\t\t<surname>");
						jats.append(TextUtilities.HTMLEncode(author.getLastName()));
						jats.append("</surname>\n");
					}
					if (author.getFirstName() != null) {
						jats.append("\t\t\t\t\t\t<given-names>");
						jats.append(TextUtilities.HTMLEncode(author.getFirstName()));
						jats.append("</given-names>\n");
					}

					jats.append("\t\t\t\t\t</name>\n");
				}

				if (author.getAffiliations() != null) {
					for (Affiliation affil : author.getAffiliations()) {
						if (affil.getKey() != null) {
							jats.append("\t\t\t\t\t<xref ref-type=\"aff\" rid=\"").append(affil.getKey()).append("\"/>\n");
							if (!affData.containsKey(affil.getKey())) {
								affData.put(affil.getKey(), affil);
							}
						}
						/*
						if (affil.getInstitutions() != null) {
							for(String inst: affil.getInstitutions()) {
								affilString.append("\t\t\t\t<institution content-type=\"orgname\">");
								affilString.append(TextUtilities.HTMLEncode(inst));
								affilString.append("</institution>\n");
							}
						}
						if (affil.getLaboratories() != null) {
							for(String labs: affil.getLaboratories()) {
								affilString.append("\t\t\t\t<institution content-type=\"orgname\">");
								affilString.append(TextUtilities.HTMLEncode(labs));
								affilString.append("</institution>\n");
							}
						}

						if (affil.getDepartments() != null) {
							for(int z = 0; z < affil.getDepartments().size(); z++) {
								affilString.append("\t\t\t\t<institution content-type=\"orgdiv").append(z+1).append("\">");
								affilString.append(TextUtilities.HTMLEncode(affil.getDepartments().get(z)));
								affilString.append("</institution>\n");
							}
						}

						if (affil.getAddressString() != null) {
							affilString.append("\t\t\t\t<addr-line>");
							affilString.append(TextUtilities.HTMLEncode(affil.getAddressString()));
							affilString.append("</addr-line>\n");
						}

						if (affil.getCountry() != null) {
							affilString.append("\t\t\t\t<country>");
							affilString.append(TextUtilities.HTMLEncode(affil.getCountry()));
							affilString.append("</country>\n");
						}
						*/

						/*
						 * Isn't supported by Texture
						 */
						/*
						if (affil.getSettlement() != null) {
							affilString.append("\t\t\t\t<city>");
							affilString.append(TextUtilities.HTMLEncode(affil.getSettlement()));
							affilString.append("</city>\n");
						}

						if (affil.getPostCode() != null) {
							affilString.append("\t\t\t\t<postal-code>");
							affilString.append(TextUtilities.HTMLEncode(affil.getPostCode()));
							affilString.append("</postal-code>\n");
						}

						affiliations.add(affilString);

						affilNumber++;
						 */
					}
				}
				jats.append("\t\t\t\t</contrib>\n");
			}

			jats.append("\t\t\t</contrib-group>\n");

			// Affiliations
			if (!affData.isEmpty()) {
				for (Map.Entry<String, Affiliation> entry: affData.entrySet()) {
					Affiliation affiliation = entry.getValue();
					jats.append("\t\t\t<aff id=\"").append(entry.getKey()).append("\">\n");
					jats.append(toJATSAuthorBlock(affiliation, "\t\t\t\t"));
					jats.append("\t\t\t</aff>\n");
				}
			}

			// TODO Publication Date
			// Article credentials
			if (biblio.getVolume() != null) {
				jats.append("\t\t\t<volume>");
				jats.append(TextUtilities.HTMLEncode(biblio.getVolume()));
				jats.append("</volume>\n");
			}

			if (biblio.getIssue() != null) {
				jats.append("\t\t\t<issue>");
				jats.append(TextUtilities.HTMLEncode(biblio.getIssue()));
				jats.append("</issue>\n");
			}

			if (biblio.getPageRange() != null) {
				StringTokenizer st = new StringTokenizer(biblio.getPageRange(), "--");
				if (st.countTokens() == 2) {
					jats.append("\t\t\t<fpage>");
					jats.append(TextUtilities.HTMLEncode(st.nextToken()));
					jats.append("</fpage>\n");

					jats.append("\t\t\t<lpage>");
					jats.append(TextUtilities.HTMLEncode(st.nextToken()));
					jats.append("</lpage>\n");
				} else {
					jats.append("\t\t\t<page-range>");
					jats.append(TextUtilities.HTMLEncode(biblio.getPageRange()));
					jats.append("</page-range>\n");
				}
			} else if (biblio.getBeginPage() != -1) {
				if (biblio.getEndPage() != -1) {
					jats.append("\t\t\t<fpage>");
					jats.append(TextUtilities.HTMLEncode(String.valueOf(biblio.getBeginPage())));
					jats.append("</fpage>\n");

					jats.append("\t\t\t<lpage>");
					jats.append(TextUtilities.HTMLEncode(String.valueOf(biblio.getEndPage())));
					jats.append("</lpage>\n");
				} else {
					jats.append("\t\t\t<fpage>");
					jats.append(TextUtilities.HTMLEncode(String.valueOf(biblio.getBeginPage())));
					jats.append("</fpage>\n");
				}
			}
		}

		// Abstract
		String abstractText = biblio.getAbstract();

		Language resLang = null;
		if (abstractText != null) {
			LanguageUtilities languageUtilities = LanguageUtilities.getInstance();
			resLang = languageUtilities.runLanguageId(abstractText);
		}
		if (resLang != null) {
			String resL = resLang.getLang();
			if (!resL.equals(doc.getLanguage())) {
				jats.append("\t\t\t<abstract xml:lang=\"").append(resL).append("\">\n");
			} else {
				jats.append("\t\t\t<abstract>\n");
			}
		} else if ((abstractText == null) || (abstractText.length() == 0)) {
			jats.append("\t\t\t<abstract/>\n");
		} else {
			jats.append("\t\t\t<abstract>\n");
		}

		if ((abstractText != null) && (abstractText.length() != 0)) {
			if ( (biblio.getLabeledAbstract() != null) && (biblio.getLabeledAbstract().length() > 0) ) {
				StringBuilder buffer = new StringBuilder();

				String tabs = "\t\t\t\t";
				try {
					buffer = toJATSTextPiece(buffer,
							biblio.getLabeledAbstract(),
							biblio,
							bds,
							false,
							new LayoutTokenization(biblio.getLayoutTokens(TaggingLabels.HEADER_ABSTRACT)),
							null,
							null,
							null,
							doc,
							config, tabs); // no figure, no table, no equation
				} catch(Exception e) {
					throw new GrobidException("An exception occurred while serializing TEI.", e);
				}
				jats.append(buffer.toString());
				jats.append("\t</abstract>\n");
			} else {
				jats.append("\t\t\t\t<p");
				jats.append(">").append(TextUtilities.HTMLEncode(abstractText)).append("</p>\n");
				jats.append("\t\t\t</abstract>\n");
			}
		}

		if ((biblio.getKeywords() != null) && (biblio.getKeywords().size() > 0)) {
			jats.append("\t\t\t<kwd-group>\n");
			for (Keyword keyword: biblio.getKeywords()) {
				jats.append("\t\t\t\t<kwd>");
				jats.append(TextUtilities.HTMLEncode(keyword.getKeyword()));
				jats.append("</kwd>\n");
			}
			//int jatsLength3 = jats.length();
			//jats.replace(jatsLength3-2, jatsLength3, "\n\t\t\t");
			jats.append("\t\t\t</kwd-group>\n");
		}

		jats.append("\t\t</article-meta>\n");
		jats.append("\t</front>\n");

		return jats;
	}

	public StringBuilder toJATSBody(StringBuilder buffer,
	                               String result,
	                               BiblioItem biblio,
	                               List<BibDataSet> bds,
	                               LayoutTokenization layoutTokenization,
	                               List<Figure> figures,
	                               List<Table> tables,
	                               List<Equation> equations,
	                               Document doc,
	                               GrobidAnalysisConfig config) throws Exception {
		if ((result == null) || (layoutTokenization == null) || (layoutTokenization.getTokenization() == null)) {
			buffer.append("\t<body/>\n");
			return buffer;
		}
		buffer.append("\t<body>\n");

		String tabs = "\t\t";
		buffer = toJATSTextPiece(buffer, result, biblio, bds, true,
				layoutTokenization, figures, tables, equations, doc, config, tabs);

		// notes are still in the body
		/*
		buffer = toTEINote(buffer, doc, config);
		*/
		buffer.append("\t</body>\n");


		return buffer;
	}

	private StringBuilder toJATSTextPiece(StringBuilder buffer,
	                                     String result,
	                                     BiblioItem biblio,
	                                     List<BibDataSet> bds,
	                                     boolean keepUnsolvedCallout,
	                                     LayoutTokenization layoutTokenization,
	                                     List<Figure> figures,
	                                     List<Table> tables,
	                                     List<Equation> equations,
	                                     Document doc,
	                                     GrobidAnalysisConfig config, String tabs) throws Exception {

		TaggingLabel lastClusterLabel = null;
		int startPosition = buffer.length();
		List<LayoutToken> tokenizations = layoutTokenization.getTokenization();

		TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULLTEXT, result, tokenizations);
		List<TaggingTokenCluster> clusters = clusteror.cluster();

		List<Element> divResults = new ArrayList<>();
		Element curSec = null;
		Element curSubSec = null;
		int curSecType = JATS_SECTION_TYPE_NONE;
		Element curParagraph = null;
		Element curList = null;
		int equationIndex = 0; // current equation index position

		for (TaggingTokenCluster cluster : clusters) {
			if (cluster == null) {
				continue;
			}

			TaggingLabel clusterLabel = cluster.getTaggingLabel();
			Engine.getCntManager().i(clusterLabel);

			if (clusterLabel.equals(TaggingLabels.SECTION) || clusterLabel.equals(TaggingLabels.SUBSECTION)) {
				String clusterContent = LayoutTokensUtil.normalizeDehyphenizeText(cluster.concatTokens());

				if (clusterLabel.equals(TaggingLabels.SECTION)) {
					curSec = jatsElement("sec");
					divResults.add(curSec);
					Element head = jatsElement("title");
					head.appendChild(clusterContent);
					curSec.appendChild(head);
					curSecType = JATS_SECTION_TYPE_SECTION;
				} else if (clusterLabel.equals(TaggingLabels.SUBSECTION)) {
					curSubSec = jatsElement("sec");
					if (curSec != null) {
						curSec.appendChild(curSubSec);
					} else {
						divResults.add(curSubSec);
					}
					Element head = jatsElement("title");
					head.appendChild(clusterContent);
					curSubSec.appendChild(head);
					curSecType = JATS_SECTION_TYPE_SUBSECTION;
				}
			} else if (clusterLabel.equals(TaggingLabels.EQUATION) ||
					clusterLabel.equals(TaggingLabels.EQUATION_LABEL)) {
				// get starting position of the cluster
				int start = -1;
				if ( (cluster.concatTokens() != null) && (cluster.concatTokens().size() > 0) ) {
					start = cluster.concatTokens().get(0).getOffset();
				}
				// get the corresponding equation
				if (start != -1) {
					Equation theEquation = null;
					if (equations != null) {
						for(int i=0; i<equations.size(); i++) {
							if (i < equationIndex)
								continue;
							Equation equation = equations.get(i);
							if (equation.getStart() == start) {
								theEquation = equation;
								equationIndex = i;
								break;
							}
						}
						if (theEquation != null) {
							Element element = jatsElement("disp-formula");
							if (theEquation.getId() != null) {
								element.addAttribute(new Attribute("id", theEquation.getId()));
							}
							if (theEquation.getLabel() != null && theEquation.getLabel().length() > 0) {
								Element labelEl = jatsElement("label");
								labelEl.appendChild(LayoutTokensUtil.normalizeText(theEquation.getLabel()));
								element.appendChild(labelEl);
							}

							if (theEquation.getContent() != null) {
								element.appendChild(LayoutTokensUtil.normalizeText(theEquation.getContent()));
							}
							insertChild(divResults, curSec, curSubSec, curSecType, element);
						}
					}
				}
			} else if (clusterLabel.equals(TaggingLabels.ITEM_BULLETED) || clusterLabel.equals(TaggingLabels.ITEM_NUMBERED)) {
				String clusterContent = LayoutTokensUtil.normalizeText(cluster.concatTokens());
				Element itemNode = jatsElement("list-item");
				Element parNode = jatsElement("p");
				parNode.appendChild(clusterContent);
				itemNode.appendChild(parNode);
				if (!MARKER_LABELS.contains(lastClusterLabel) && ((lastClusterLabel != TaggingLabels.ITEM_BULLETED) && ((lastClusterLabel != TaggingLabels.ITEM_NUMBERED)))) {
					curList = jatsElement("list");
					if (clusterLabel.equals(TaggingLabels.ITEM_BULLETED)) {
						curList.addAttribute(new Attribute("list-type", "bullet"));
					} else if(clusterLabel.equals(TaggingLabels.ITEM_NUMBERED)) {
						curList.addAttribute(new Attribute("list-type", "order"));
					}
					insertChild(divResults, curSec, curSubSec, curSecType, curList);
				}
				if (curList != null) {
					curList.appendChild(itemNode);
				}
			} else if (clusterLabel.equals(TaggingLabels.QUOTE)) {
				String clusterContent = LayoutTokensUtil.normalizeText(cluster.concatTokens());
				Element quote = jatsElement("disp-quote");
				quote.appendChild(clusterContent);
				insertChild(divResults, curSec, curSubSec, curSecType, quote);
			} else if (clusterLabel.equals(TaggingLabels.OTHER)) {
				// TODO check where this should appear only in the header
			} else if (clusterLabel.equals(TaggingLabels.PARAGRAPH)) {
				String clusterContent = LayoutTokensUtil.normalizeDehyphenizeText(cluster.concatTokens());
				if (isNewParagraph(lastClusterLabel, curParagraph)) {
					curParagraph = jatsElement("p");
					if (config.isGenerateTeiIds()) {
						String divID = KeyGen.getKey().substring(0, 7);
						addXmlId(curParagraph, "_" + divID);
					}

					insertChild(divResults, curSec, curSubSec, curSecType, curParagraph);
				}
				curParagraph.appendChild(clusterContent);
			} else if (MARKER_LABELS.contains(clusterLabel)) {
				List<LayoutToken> refTokens = cluster.concatTokens();
				refTokens = LayoutTokensUtil.dehyphenize(refTokens);
				String chunkRefString = LayoutTokensUtil.toText(refTokens);

				Element parent;
				if (curParagraph != null) {
					parent = curParagraph;
					//parent.appendChild(new Text(" "));
				} else {
					curParagraph = jatsElement("p");
					insertChild(divResults, curSec, curSubSec, curSecType, curParagraph);
					parent = curParagraph;
				}

				List<Node> refNodes;
				if (clusterLabel.equals(TaggingLabels.CITATION_MARKER)) {
					refNodes = markReferencesJATSLuceneBased(refTokens,
							doc.getReferenceMarkerMatcher(),
							config.isGenerateTeiCoordinates("ref"),
							keepUnsolvedCallout);

				} else if (clusterLabel.equals(TaggingLabels.FIGURE_MARKER)) {
					refNodes = markReferencesFigureJATS(chunkRefString, refTokens, figures,
							config.isGenerateTeiCoordinates("ref"));
				} else if (clusterLabel.equals(TaggingLabels.TABLE_MARKER)) {
					refNodes = markReferencesTableJATS(chunkRefString, refTokens, tables,
							config.isGenerateTeiCoordinates("ref"));
				} else if (clusterLabel.equals(TaggingLabels.EQUATION_MARKER)) {
					refNodes = markReferencesEquationJATS(chunkRefString, refTokens, equations,
							config.isGenerateTeiCoordinates("ref"));
				} else if (clusterLabel.equals(TaggingLabels.NOTE_MARKER)) {
					refNodes = markReferencesEquationJATS(chunkRefString, refTokens, equations,
							config.isGenerateTeiCoordinates("ref"));
				} else {
					throw new IllegalStateException("Unsupported marker type: " + clusterLabel);
				}

				if (refNodes != null) {
					for (Node n : refNodes) {
						parent.appendChild(n);
					}
				}
			} else if (clusterLabel.equals(TaggingLabels.FIGURE)) {
				// TODO append figure here
			} else if (clusterLabel.equals(TaggingLabels.TABLE)) {
				// TODO append table here
			}

			lastClusterLabel = cluster.getTaggingLabel();
		}

		Element secFigures = null;
		if (figures != null || tables != null) {
			secFigures = jatsElement("sec");
			secFigures.addAttribute(new Attribute("sec-type", "supplementary-material"));
			divResults.add(secFigures);
		}

		if (figures != null) {
			for (Figure figure : figures) {
				Element figSeg = figureToJats(figure, config, doc);
				if (figSeg != null) {
					secFigures.appendChild(figSeg);
				}
			}
		}

		if (tables != null) {
			for (Table table : tables) {
				Element tabSeg = tableToJats(table, config, doc);
				if (tabSeg != null) {
					secFigures.appendChild(tabSeg);
				}
			}
		}

		toPrettyXML(buffer, divResults, tabs);

		return buffer;
	}

	private void insertChild(List<Element> divResults, Element curSec, Element curSubSec, int curSecType, Element curElement) {
		switch (curSecType) {
			case JATS_SECTION_TYPE_NONE:
				divResults.add(curElement);
				break;
			case JATS_SECTION_TYPE_SECTION:
				curSec.appendChild(curElement);
				break;
			case JATS_SECTION_TYPE_SUBSECTION:
				curSubSec.appendChild(curElement);
				break;
		}
	}

	private void toPrettyXML(StringBuilder buffer, List<Element> divResults, String tabs) throws IOException {
		buffer.append(tabs);
		int restrictLength = (tabs.length() - 4) * 2;
		for (Element divResult: divResults) {
			OutputStream outputStream = new ByteArrayOutputStream();
			nu.xom.Document xomDocument = new nu.xom.Document(divResult);
			Serializer serializer = new Serializer(outputStream);
			serializer.setIndent(4);
			serializer.setMaxLength(88 - restrictLength);
			serializer.write(xomDocument);
			String xomDocumentString = outputStream.toString();
			xomDocumentString = xomDocumentString.substring(xomDocumentString.indexOf('\n')+1);
			xomDocumentString = xomDocumentString.replace("\n", "\n" + tabs);
			buffer.append(xomDocumentString);
		}

		int bufferLength = buffer.length();
		buffer.replace(bufferLength - 2, bufferLength, "");
	}

	private org.grobid.core.utilities.Pair<String, String> getSectionNumber(String text) {
		Matcher m1 = BasicStructureBuilder.headerNumbering1.matcher(text);
		Matcher m2 = BasicStructureBuilder.headerNumbering2.matcher(text);
		Matcher m3 = BasicStructureBuilder.headerNumbering3.matcher(text);
		Matcher m = null;
		String numb = null;
		if (m1.find()) {
			numb = m1.group(0);
			m = m1;
		} else if (m2.find()) {
			numb = m2.group(0);
			m = m2;
		} else if (m3.find()) {
			numb = m3.group(0);
			m = m3;
		}
		if (numb != null) {
			text = text.replace(numb, "").trim();
			numb = numb.replace(" ", "");
			return new org.grobid.core.utilities.Pair<>(text, numb);
		} else {
			return null;
		}
	}

	private Element jatsElement(String tagName) {
		return new Element(tagName, null);
	}

	private boolean isNewParagraph(TaggingLabel lastClusterLabel, Element curParagraph) {
		return (!MARKER_LABELS.contains(lastClusterLabel) && lastClusterLabel != TaggingLabels.FIGURE
				&& lastClusterLabel != TaggingLabels.TABLE) || curParagraph == null;
	}

	public List<Node> markReferencesJATSLuceneBased(List<LayoutToken> refTokens,
	                                               ReferenceMarkerMatcher markerMatcher,
	                                               boolean generateCoordinates,
	                                               boolean keepUnsolvedCallout) throws EntityMatcherException {
		if ( (refTokens == null) || (refTokens.size() == 0) )
			return null;
		String text = LayoutTokensUtil.toText(refTokens);
		if (text == null || text.trim().length() == 0 || text.endsWith("</xref>") || text.startsWith("<xref") || markerMatcher == null)
			return Collections.<Node>singletonList(new Text(text));

		boolean spaceEnd = false;
		text = text.replace("\n", " ");
		if (text.endsWith(" "))
			spaceEnd = true;
		List<Node> nodes = new ArrayList<>();
		List<ReferenceMarkerMatcher.MatchResult> matchResults = markerMatcher.match(refTokens);
		if (matchResults != null) {
			for (ReferenceMarkerMatcher.MatchResult matchResult : matchResults) {
				String markerText = LayoutTokensUtil.normalizeText(matchResult.getText());
				String coords = null;
				if (generateCoordinates && matchResult.getTokens() != null) {
					coords = LayoutTokensUtil.getCoordsString(matchResult.getTokens());
				}

				Element ref = jatsElement("xref");
				ref.addAttribute(new Attribute("ref-type", "bibr"));

				if (coords != null) {
					ref.addAttribute(new Attribute("coords", coords));
				}
				ref.appendChild(markerText);

				boolean solved = false;
				if (matchResult.getBibDataSet() != null) {
					ref.addAttribute(new Attribute("rid", "b" + matchResult.getBibDataSet().getResBib().getOrdinal()));
					solved = true;
				}
				if ( solved || (!solved && keepUnsolvedCallout) )
					nodes.add(ref);
				else
					nodes.add(textNode(matchResult.getText()));
			}
		}
		if (spaceEnd)
			nodes.add(new Text(" "));
		return nodes;
	}


	public List<Node> markReferencesFigureJATS(String text,
	                                          List<LayoutToken> refTokens,
	                                          List<Figure> figures,
	                                          boolean generateCoordinates) {
		if (text == null || text.trim().isEmpty()) {
			return null;
		}

		List<Node> nodes = new ArrayList<>();

		String textLow = text.toLowerCase();
		String bestFigure = null;

		if (figures != null) {
			for (Figure figure : figures) {
				if ((figure.getLabel() != null) && (figure.getLabel().length() > 0)) {
					String label = TextUtilities.cleanField(figure.getLabel(), false);
					if ((label.length() > 0) &&
							(textLow.contains(label.toLowerCase()))) {
						bestFigure = figure.getId();
						break;
					}
				}
			}
		}

		boolean spaceEnd = false;
		text = text.replace("\n", " ");
		if (text.endsWith(" "))
			spaceEnd = true;
		text = text.trim();

		String coords = null;
		if (generateCoordinates && refTokens != null) {
			coords = LayoutTokensUtil.getCoordsString(refTokens);
		}

		Element ref = jatsElement("xref");
		ref.addAttribute(new Attribute("ref-type", "fig"));

		if (coords != null) {
			ref.addAttribute(new Attribute("coords", coords));
		}
		ref.appendChild(text);

		if (bestFigure != null) {
			ref.addAttribute(new Attribute("rid", "fig_" + bestFigure));
		}
		nodes.add(ref);
		if (spaceEnd)
			nodes.add(new Text(" "));
		return nodes;
	}

	public List<Node> markReferencesTableJATS(String text, List<LayoutToken> refTokens,
	                                         List<Table> tables,
	                                         boolean generateCoordinates) {
		if (text == null || text.trim().isEmpty()) {
			return null;
		}

		List<Node> nodes = new ArrayList<>();

		String textLow = text.toLowerCase();
		String bestTable = null;
		if (tables != null) {
			for (Table table : tables) {
				if ((table.getLabel() != null) && (table.getLabel().length() > 0)) {
					String label = TextUtilities.cleanField(table.getLabel(), false);
					if ((label.length() > 0) &&
							(textLow.contains(label.toLowerCase()))) {
						bestTable = table.getId();
						break;
					}
				}
			}
		}

		boolean spaceEnd = false;
		text = text.replace("\n", " ");
		if (text.endsWith(" "))
			spaceEnd = true;
		text = text.trim();

		String coords = null;
		if (generateCoordinates && refTokens != null) {
			coords = LayoutTokensUtil.getCoordsString(refTokens);
		}

		Element ref = jatsElement("xref");
		ref.addAttribute(new Attribute("ref-type", "table"));

		if (coords != null) {
			ref.addAttribute(new Attribute("coords", coords));
		}
		ref.appendChild(text);
		if (bestTable != null) {
			ref.addAttribute(new Attribute("rid", "tab_" + bestTable));
		}
		nodes.add(ref);
		if (spaceEnd)
			nodes.add(new Text(" "));
		return nodes;
	}

	public List<Node> markReferencesEquationJATS(String text,
	                                            List<LayoutToken> refTokens,
	                                            List<Equation> equations,
	                                            boolean generateCoordinates) {
		if (text == null || text.trim().isEmpty()) {
			return null;
		}

		List<Node> nodes = new ArrayList<>();

		String textLow = text.toLowerCase();
		String bestFormula = null;
		if (equations != null) {
			for (Equation equation : equations) {
				if ((equation.getLabel() != null) && (equation.getLabel().length() > 0)) {
					String label = TextUtilities.cleanField(equation.getLabel(), false);
					if ((label.length() > 0) &&
							(textLow.contains(label.toLowerCase()))) {
						bestFormula = equation.getId();
						break;
					}
				}
			}
		}

		boolean spaceEnd = false;
		text = text.replace("\n", " ");
		if (text.endsWith(" "))
			spaceEnd = true;
		text = text.trim();

		String coords = null;
		if (generateCoordinates && refTokens != null) {
			coords = LayoutTokensUtil.getCoordsString(refTokens);
		}

		Element ref = jatsElement("xref");
		ref.addAttribute(new Attribute("ref-type", "formula"));

		if (coords != null) {
			ref.addAttribute(new Attribute("coords", coords));
		}
		ref.appendChild(text);
		if (bestFormula != null) {
			ref.addAttribute(new Attribute("rid", "formula_" + bestFormula));
		}
		nodes.add(ref);
		if (spaceEnd)
			nodes.add(new Text(" "));
		return nodes;
	}

	private Element figureToJats(Figure figure, GrobidAnalysisConfig config, Document doc) {
		if (StringUtils.isEmpty(figure.getHeader()) && StringUtils.isEmpty(figure.getCaption()) && CollectionUtils.isEmpty(figure.getGraphicObjects())) {
			return null;
		}
		Element figureElement = jatsElement("fig");
		if (figure.getId() != null) {
			figureElement.addAttribute(new Attribute("id", "fig_" + figure.getId()));
		}

		if (figure.getLabel() != null) {
			Element labelEl = jatsElement("label");
			labelEl.appendChild(LayoutTokensUtil.normalizeText(figure.getLabel()));
			figureElement.appendChild(labelEl);
		}

		if (figure.getHeader() != null || figure.getCaption() != null) {
			Element captionWrapper = jatsElement("caption");
			figureElement.appendChild(captionWrapper);

			if (figure.getHeader() != null) {
				Element head = jatsElement("title");
				head.appendChild(LayoutTokensUtil.normalizeText(figure.getHeader()));
				captionWrapper.appendChild(head);
			}

			if (figure.getCaption() != null) {

				Element desc = jatsElement("p");
				if (config.isGenerateTeiIds()) {
					String divID = KeyGen.getKey().substring(0, 7);
					addXmlId(desc, "_" + divID);
				}

				// if the segment has been parsed with the full text model we further extract the clusters
				// to get the bibliographical references
				if ( (figure.getLabeledCaption() != null) && (figure.getLabeledCaption().length() > 0) ) {
					TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULLTEXT, figure.getLabeledCaption(), figure.getCaptionLayoutTokens());
					List<TaggingTokenCluster> clusters = clusteror.cluster();

					for (TaggingTokenCluster cluster : clusters) {
						if (cluster == null) {
							continue;
						}

						TaggingLabel clusterLabel = cluster.getTaggingLabel();
						//String clusterContent = LayoutTokensUtil.normalizeText(cluster.concatTokens());
						String clusterContent = LayoutTokensUtil.normalizeDehyphenizeText(cluster.concatTokens());
						if (clusterLabel.equals(TaggingLabels.CITATION_MARKER)) {
							try {
								List<Node> refNodes = this.markReferencesJATSLuceneBased(
										cluster.concatTokens(),
										doc.getReferenceMarkerMatcher(),
										config.isGenerateTeiCoordinates("ref"),
										false);
								if (refNodes != null) {
									for (Node n : refNodes) {
										desc.appendChild(n);
									}
								}
							} catch(Exception e) {
								LOGGER.warn("Problem when serializing JATS fragment for figure caption", e);
							}
						} else {
							desc.appendChild(textNode(clusterContent));
						}
					}
				} else {
					desc.appendChild(LayoutTokensUtil.normalizeText(figure.getCaption()).trim());
					//Element desc = XmlBuilderUtils.teiElement("figDesc",
					//    LayoutTokensUtil.normalizeText(caption.toString()));
				}
				captionWrapper.appendChild(desc);
			}
		}


		if ((figure.getGraphicObjects() != null) && (figure.getGraphicObjects().size() > 0)) {
			for (GraphicObject graphicObject : figure.getGraphicObjects()) {
				Element go = jatsElement("graphic");
				String uri = graphicObject.getURI();
				if (uri != null) {
					go.addAttribute(new Attribute("url", uri));
				}

				if (graphicObject.getBoundingBox() != null) {
					go.addAttribute(new Attribute("coords", graphicObject.getBoundingBox().toString()));
				}

				go.addAttribute(new Attribute("type", graphicObject.getType().name().toLowerCase()));
				if (graphicObject.isMask()) {
					go.addAttribute(new Attribute("mask", "true"));
				}
				figureElement.appendChild(go);
			}
		}
		return figureElement;
	}

	private Element tableToJats(Table table, GrobidAnalysisConfig config, Document doc) {
		if (StringUtils.isEmpty(table.getHeader()) && StringUtils.isEmpty(table.getCaption())) {
			return null;
		}

		Element tableElement = jatsElement("table-wrap");
		if (table.getId() != null) {
			tableElement.addAttribute(new Attribute("id", "tab_" + table.getId()));
		}

		if (table.getLabel() != null) {
			Element labelEl = jatsElement("label");
			labelEl.appendChild(LayoutTokensUtil.normalizeText(table.getLabel()));
			tableElement.appendChild(labelEl);
		}

		if (table.getHeader() != null || table.getCaption() != null) {
			Element captionEl = jatsElement("caption");
			tableElement.appendChild(captionEl);

			if (table.getHeader() != null) {
				Element head = jatsElement("title");
				head.appendChild(LayoutTokensUtil.normalizeText(table.getHeader()));
				captionEl.appendChild(head);
			}

			if (table.getCaption() != null) {
				// if the segment has been parsed with the full text model we further extract the clusters
				// to get the bibliographical references

				Element desc = jatsElement("p");
				if (config.isGenerateTeiIds()) {
					String divID = KeyGen.getKey().substring(0, 7);
					addXmlId(desc, "_" + divID);
				}

				if ( (table.getLabeledCaption() != null) && (table.getLabeledCaption().length() > 0) ) {
					TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULLTEXT, table.getLabeledCaption(), table.getCaptionLayoutTokens());
					List<TaggingTokenCluster> clusters = clusteror.cluster();
					for (TaggingTokenCluster cluster : clusters) {
						if (cluster == null) {
							continue;
						}

						TaggingLabel clusterLabel = cluster.getTaggingLabel();
						//String clusterContent = LayoutTokensUtil.normalizeText(cluster.concatTokens());
						String clusterContent = LayoutTokensUtil.normalizeDehyphenizeText(cluster.concatTokens());
						if (clusterLabel.equals(TaggingLabels.CITATION_MARKER)) {
							try {
								List<Node> refNodes = this.markReferencesJATSLuceneBased(
										cluster.concatTokens(),
										doc.getReferenceMarkerMatcher(),
										config.isGenerateTeiCoordinates("ref"),
										false);
								if (refNodes != null) {
									for (Node n : refNodes) {
										desc.appendChild(n);
									}
								}
							} catch(Exception e) {
								LOGGER.warn("Problem when serializing TEI fragment for figure caption", e);
							}
						} else {
							desc.appendChild(textNode(clusterContent));
						}
					}
				} else {
					desc.appendChild(LayoutTokensUtil.normalizeText(table.getCaption()).trim());
				}

				captionEl.appendChild(desc);
			}

			Element contentEl = jatsElement("table");
			processTableContent(contentEl, table.getContentTokens());
			tableElement.appendChild(contentEl);
		}

		return tableElement;
	}

	/**
	 *
	 * @param contentEl table element to append parsed rows and cells.
	 * @param contentTokens tokens that are used to build cells
	 * Line-based algorithm for parsing tables, uses tokens' coordinates to identify lines
	 */
	void processTableContent(Element contentEl, List<LayoutToken> contentTokens) {
		// Join Layout Tokens into cell lines originally created by PDFAlto
		List<LinePart> lineParts = Line.extractLineParts(contentTokens);

		// Build lines by comparing borders
		List<Line> lines = Line.extractLines(lineParts);

		// Build rows and cells
		List<Row> rows = Row.extractRows(lines);

		int columnCount = Row.columnCount(rows);

		Row.insertEmptyCells(rows, columnCount);

		Row.mergeMulticolumnCells(rows);

		for (Row row: rows) {
			Element tr = jatsElement("tr");
			contentEl.appendChild(tr);
			List<Cell> cells = row.getContent();
			for (Cell cell: cells) {
				Element td = jatsElement("td");
				tr.appendChild(td);
				if (cell.getColspan() > 1) {
					td.addAttribute(new Attribute("colspan", Integer.toString(cell.getColspan())));
				}
				td.appendChild(cell.getText().trim());
			}
		}
	}

	public StringBuilder toJATSReferences(StringBuilder jats,
	                                     List<BibDataSet> bds,
	                                     GrobidAnalysisConfig config) throws Exception {

		if ((bds == null) || (bds.size() == 0))
			jats.append("\t\t\t<ref-list/>\n");
		else {
			jats.append("\t\t\t<ref-list>\n");

			int p = 0;
			if (bds.size() > 0) {
				for (BibDataSet bib : bds) {
					BiblioItem bit = bib.getResBib();
					bit.setReference(bib.getRawBib());
					if (bit != null) {
						jats.append(biblioToJats(bit, p, config));
					}
					p++;
				}
			}
			jats.append("\t\t\t</ref-list>\n");
		}

		return jats;
	}

	private String biblioToJats(BiblioItem bit, int n, GrobidAnalysisConfig config) {
		StringBuilder jats = new StringBuilder();
		boolean generateIDs = config.isGenerateTeiIds();

		try {
			// Determine reference type
			String bitType = biblioGetType(bit);
			if (bitType != null) {
				bit.setType(bitType);
			}

			jats.append("\t\t\t\t<ref");
			jats.append(" ");
			if (!StringUtils.isEmpty(bit.getLanguage())) {
				if (n == -1) {
					jats.append("xml:lang=\"").append(bit.getLanguage()).append("\">\n");
				} else {
					String jatsId = "b" + n;
					jats.append("xml:lang=\"").append(bit.getLanguage()).append("\" id=\"").append(jatsId).append("\">\n");
				}
				// TBD: we need to ensure that the language is normalized following xml lang attributes !
			} else {
				String jatsId = "b" + n;
				jats.append("id=\"").append(jatsId).append("\">\n");
			}

			jats.append("\t\t\t\t\t<element-citation");
			if (bit.getType() != null) {
				jats.append(" publication-type=\"").append(TextUtilities.HTMLEncode(bit.getType())).append("\">\n");
			} else {
				jats.append(">\n");
			}

			if (bit.getFullAuthors() != null || bit.getCollaboration() != null) {
				jats.append("\t\t\t\t\t\t<person-group person-group-type=\"author\">\n");
				for (Person author: bit.getFullAuthors()) {
					biblioWritePerson(jats, author);
				}

				if (bit.getCollaboration() != null) {
					jats.append("\t\t\t\t\t\t\t<collab>");
					jats.append(TextUtilities.HTMLEncode(bit.getCollaboration()));
					jats.append("</collab>\n");
				}

				jats.append("\t\t\t\t\t\t</person-group>\n");
			}

			if (bit.getFullEditors() != null) {
				jats.append("\t\t\t\t\t\t<person-group person-group-type=\"editor\">\n");
				for (Person editor: bit.getFullEditors()) {
					biblioWritePerson(jats, editor);
				}
				jats.append("\t\t\t\t\t\t</person-group>\n");
			}

			if (bit.getType() != null) {

				if (!bit.getType().equals(BIBLIO_TYPE_BOOK) && !bit.getType().equals(BIBLIO_TYPE_CHAPTER)) {
					if (bit.getTitle() != null) {
						jats.append("\t\t\t\t\t\t<article-title>");
						jats.append(TextUtilities.HTMLEncode(bit.getTitle()));
						jats.append("</article-title>\n");
					}
				}

				if (bit.getType().equals(BIBLIO_TYPE_JOURNAL)) {
					if (bit.getJournal() != null) {
						jats.append("\t\t\t\t\t\t<source>");
						jats.append(TextUtilities.HTMLEncode(bit.getJournal()));
						jats.append("</source>\n");
					} else if (bit.getJournalAbbrev() != null) {
						jats.append("\t\t\t\t\t\t<source>");
						jats.append(TextUtilities.HTMLEncode(bit.getJournalAbbrev()));
						jats.append("</source>\n");
					}
				}

				if (bit.getType().equals(BIBLIO_TYPE_JOURNAL) || bit.getType().equals(BIBLIO_TYPE_ARTICLE)) {
					if (bit.getIssue() != null) {
						jats.append("\t\t\t\t\t\t<issue>");
						jats.append(TextUtilities.HTMLEncode(bit.getIssue()));
						jats.append("</issue>\n");
					}

					if (bit.getDOI() != null) {
						jats.append("\t\t\t\t\t\t<pub-id pub-id-type=\"doi\">");
						jats.append(TextUtilities.HTMLEncode(bit.getDOI()));
						jats.append("</pub-id>\n");
					}

					if (bit.getPMID() != null) {
						jats.append("\t\t\t\t\t\t<pub-id pub-id-type=\"pmid\">");
						jats.append(TextUtilities.HTMLEncode(bit.getPMID()));
						jats.append("</pub-id>\n");
					}

					if (bit.getPMCID() != null) {
						jats.append("\t\t\t\t\t\t<pub-id pub-id-type=\"pmcid\">");
						jats.append(TextUtilities.HTMLEncode(bit.getPMCID()));
						jats.append("</pub-id>\n");
					}
				}

				if (bit.getType().equals(BIBLIO_TYPE_BOOK) || bit.getType().equals(BIBLIO_TYPE_CHAPTER)) {
					if (bit.getBookTitle() != null) {
						jats.append("\t\t\t\t\t\t<source>");
						jats.append(TextUtilities.HTMLEncode(bit.getBookTitle()));
						jats.append("</source>\n");
					}

					if (bit.getPublisher() != null) {
						jats.append("\t\t\t\t\t\t<publisher-name>");
						jats.append(TextUtilities.HTMLEncode(bit.getPublisher()));
						jats.append("</publisher-name>\n");
					}

					if (bit.getPublisherPlace() != null) {
						jats.append("\t\t\t\t\t\t<publisher-loc>");
						jats.append(TextUtilities.HTMLEncode(bit.getPublisherPlace()));
						jats.append("</publisher-loc>\n");
					}

					if (bit.getEdition() != null) {
						jats.append("\t\t\t\t\t\t<edition>");
						jats.append(TextUtilities.HTMLEncode(bit.getEdition()));
						jats.append("</edition>\n");
					}
				}

				if (bit.getType().equals(BIBLIO_TYPE_CHAPTER)) {
					if (bit.getTitle() != null) {
						jats.append("\t\t\t\t\t\t<chapter-title>");
						jats.append(TextUtilities.HTMLEncode(bit.getTitle()));
						jats.append("</chapter-title>\n");
					}
				}

				if (bit.getType().equals(BIBLIO_TYPE_CONFERENCE)) {
					if (bit.getEvent() != null) {
						jats.append("\t\t\t\t\t\t<conf-name>");
						jats.append(TextUtilities.HTMLEncode(bit.getEvent()));
						jats.append("</conf-name>\n");
					}

					if (bit.getLocation() != null) {
						jats.append("\t\t\t\t\t\t<conf-loc>");
						jats.append(TextUtilities.HTMLEncode(bit.getLocation()));
						jats.append("</conf-loc>\n");
					}
				}

				if (bit.getType().equals(BIBLIO_TYPE_WEBPAGE)) {
					if (bit.getWeb() != null) {
						jats.append("\t\t\t\t\t\t<source>");
						jats.append(TextUtilities.HTMLEncode(bit.getWeb()));
						jats.append("</source>\n");
					}
				}

				if (bit.getVolume() != null) {
					jats.append("\t\t\t\t\t\t<volume>");
					jats.append(TextUtilities.HTMLEncode(bit.getVolume()));
					jats.append("</volume>\n");
				}

				if (bit.getYear() != null) {
					jats.append("\t\t\t\t\t\t<year>");
					jats.append(TextUtilities.HTMLEncode(bit.getYear()));
					jats.append("</year>\n");
				}

				if (bit.getBeginPage() != -1) {
					jats.append("\t\t\t\t\t\t<fpage>");
					jats.append(bit.getBeginPage());
					jats.append("</fpage>\n");
				}

				if (bit.getEndPage() != -1) {
					jats.append("\t\t\t\t\t\t<lpage>");
					jats.append(bit.getEndPage());
					jats.append("</lpage>\n");
				}

				if (bit.getURI() != null) {
					jats.append("\t\t\t\t\t\t<uri>");
					jats.append(TextUtilities.HTMLEncode(bit.getURI()));
					jats.append("</uri>\n");
				}

				if (bit.getDay() != null) {
					jats.append("\t\t\t\t\t\t<day>");
					jats.append(TextUtilities.HTMLEncode(bit.getDay()));
					jats.append("</day>\n");
				}

				if (bit.getMonth() != null) {
					jats.append("\t\t\t\t\t\t<month>");
					jats.append(TextUtilities.HTMLEncode(bit.getDay()));
					jats.append("</month>\n");
				}
			}

			jats.append("\t\t\t\t\t</element-citation>\n");
			jats.append("\t\t\t\t</ref>\n");
		} catch (Exception e) {
			throw new GrobidException("Cannot convert  bibliographical item into a TEI, " +
					"because of nested exception.", e);
		}

		return jats.toString();
	}

	private String biblioGetType(BiblioItem bit) {
		if (bit.getType() != null) return null;

		if (bit.getEvent() != null) {
			return BIBLIO_TYPE_CONFERENCE;
		}

		if (bit.getJournal() != null || bit.getJournalAbbrev() != null) {
			return BIBLIO_TYPE_JOURNAL;
		}

		if (bit.getBookTitle() != null && bit.getTitle() != null) {
			return BIBLIO_TYPE_CHAPTER;
		}

		if (bit.getBookTitle() != null) {
			return BIBLIO_TYPE_BOOK;
		}

		if (bit.getURI() != null || bit.getWeb() != null) {
			return BIBLIO_TYPE_WEBPAGE;
		}

		// The default one; Texture error otherwise;
		return BIBLIO_TYPE_ARTICLE;
	}

	private void biblioWritePerson(StringBuilder jats, Person person) {
		jats.append("\t\t\t\t\t\t\t<name>\n");
		if (person.getLastName() != null) {
			jats.append("\t\t\t\t\t\t\t\t<surname>");
			jats.append(TextUtilities.HTMLEncode(person.getLastName()));
			jats.append("</surname>\n");
		}

		if (person.getFirstName() != null || person.getMiddleName() != null) {
			jats.append("\t\t\t\t\t\t\t\t<given-names>");
			if (person.getFirstName() != null) {
				jats.append(TextUtilities.HTMLEncode(person.getFirstName()));
			}

			if (person.getMiddleName() != null) {
				jats.append(" ");
				jats.append(TextUtilities.HTMLEncode(person.getMiddleName()));
			}
			jats.append("</given-names>\n");
		}
		jats.append("\t\t\t\t\t\t\t</name>\n");
	}

	public StringBuilder toJATSAcknowledgement(StringBuilder buffer,
	                                          String reseAcknowledgement,
	                                          List<LayoutToken> tokenizationsAcknowledgement,
	                                          List<BibDataSet> bds,
	                                          GrobidAnalysisConfig config) throws Exception {
		if ((reseAcknowledgement == null) || (tokenizationsAcknowledgement == null)) {
			return buffer;
		}

		buffer.append("\t\t\t<ack>\n");
		StringBuilder buffer2 = new StringBuilder();

		String tabs = "\t\t\t\t";

		buffer2 = toJATSTextPiece(buffer2, reseAcknowledgement, null, bds, false,
				new LayoutTokenization(tokenizationsAcknowledgement), null, null, null, doc, config, tabs);
		buffer2.append("\t</ack>\n");
		buffer.append(buffer2);

		return buffer;
	}

	public StringBuilder toJATSAnnex(StringBuilder buffer,
	                                String result,
	                                BiblioItem biblio,
	                                List<BibDataSet> bds,
	                                List<LayoutToken> tokenizations,
	                                Document doc,
	                                GrobidAnalysisConfig config) throws Exception {
		if ((result == null) || (tokenizations == null)) {
			return buffer;
		}

		buffer.append("\t\t\t<app-group>\n");
		buffer.append("\t\t\t\t<app>\n");

		String tabs = "\t\t\t\t\t";

		buffer = toJATSTextPiece(buffer, result, biblio, bds, true,
				new LayoutTokenization(tokenizations), null, null, null, doc, config, tabs);
		buffer.append("\t\t\t\t</app>\n");
		buffer.append("\t\t\t</app-group>\n");

		return buffer;
	}

	public StringBuilder toJATSAuthorBlock(Affiliation affiliation, String tabs) {
		StringBuilder buffer = new StringBuilder();
		if (affiliation.getInstitutions() != null) {
			for(String inst: affiliation.getInstitutions()) {
				buffer.append(tabs).append("<institution content-type=\"orgname\">");
				buffer.append(TextUtilities.HTMLEncode(inst));
				buffer.append("</institution>\n");
			}
		}
		if (affiliation.getLaboratories() != null) {
			for(String labs: affiliation.getLaboratories()) {
				buffer.append(tabs).append("<institution content-type=\"orgname\">");
				buffer.append(TextUtilities.HTMLEncode(labs));
				buffer.append("</institution>\n");
			}
		}

		if (affiliation.getDepartments() != null) {
			for(int z = 0; z < affiliation.getDepartments().size(); z++) {
				buffer.append(tabs).append("<institution content-type=\"orgdiv").append(z+1).append("\">");
				buffer.append(TextUtilities.HTMLEncode(affiliation.getDepartments().get(z)));
				buffer.append("</institution>\n");
			}
		}

		if (affiliation.getAddressString() != null) {
			buffer.append(tabs).append("<addr-line>");
			buffer.append(TextUtilities.HTMLEncode(affiliation.getAddressString()));
			buffer.append("</addr-line>\n");
		}

		if (affiliation.getCountry() != null) {
			buffer.append(tabs).append("<country>");
			buffer.append(TextUtilities.HTMLEncode(affiliation.getCountry()));
			buffer.append("</country>\n");
		}

		if (affiliation.getSettlement() != null) {
			buffer.append(tabs).append("<city>");
			buffer.append(TextUtilities.HTMLEncode(affiliation.getSettlement()));
			buffer.append("</city>\n");
		}

		if (affiliation.getAddressString() != null) {
			buffer.append(tabs).append("<addr-line content-type=\"street-address\">");
			buffer.append(TextUtilities.HTMLEncode(affiliation.getAddressString()));
			buffer.append("</addr-line>\n");
		}

		if (affiliation.getPostCode() != null) {
			buffer.append(tabs).append("<postal-code>");
			buffer.append(TextUtilities.HTMLEncode(affiliation.getPostCode()));
			buffer.append("</postal-code>\n");
		}

		return buffer;
	}
}
