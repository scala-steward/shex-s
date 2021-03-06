grammar SchemaMappingsDoc;

schemaMappingsDoc
 : directive* mappings EOF;  // leading CODE

directive
 : baseDecl
 | prefixDecl
 | importDecl
 ;

baseDecl
 : KW_BASE  IRIREF
 ;

prefixDecl
 : KW_PREFIX PNAME_NS IRIREF
 ;

importDecl
 : KW_IMPORT iri
 ;

mappings 
 : mapping (';' mapping ) *
 ;

mapping
 : shapePathExpr '~>' iri
 ; 

shapePathExpr
 : absolutePathExpr
 | relativePathExpr
 ;

absolutePathExpr
 : KW_SLASH  relativePathExpr
 ;

relativePathExpr
 : stepExpr (KW_SLASH stepExpr) *
 ;

stepExpr
 : contextTest ? exprIndex   # exprIndexStep
 | contextTest               # contextStep
 ;

contextTest
 : shapeExprContext
 | tripleExprContext
 ;

shapeExprContext
 : KW_ShapeAnd
 | KW_ShapeOr
 | KW_ShapeNot
 | KW_NodeConstraint
 | KW_Shape
 ;

tripleExprContext
 : KW_EachOf
 | KW_OneOf
 | KW_TripleConstraint
 ;

exprIndex
 : shapeExprIndex
 | tripleExprIndex
 ;

shapeExprIndex
 : '@' (INTEGER | shapeExprLabel)
 ;

tripleExprIndex
 : INTEGER | tripleExprLabel
 ;

shapeExprLabel
 : iri
 | blankNodeLabel
 ;

tripleExprLabel
 : (iri | blankNodeLabel) INTEGER?
 ;

blankNodeLabel
 : blankNode
 ;

iri
 : IRIREF
 | prefixedName
 ;

prefixedName
 : PNAME_LN
 | PNAME_NS
 ;

blankNode
 : BLANK_NODE_LABEL
 ;

MapsTo
 : '~>'
 ;

KW_PREFIX
 : P R E F I X
 ;

KW_ShapeAnd
 : S H A P E A N D
 ;

KW_ShapeOr
 : S H A P E O R
 ;

KW_ShapeNot
 : S H A P E N O T
 ;

KW_NodeConstraint
 : N O D E C O N S T R A I N T
 ;

KW_EachOf
 : E A C H O F
 ;

KW_OneOf
 : O N E O F
 ;

KW_Shape
 : S H A P E
 ;

KW_TripleConstraint
 : T R I P L E C O N S T R A I N T
 ;

KW_SLASH
 : '/'
 ;



// BNF: predicate ::= iri | RDF_TYPE
predicate
 : iri
 | rdfType
 ;

rdfType
 : RDF_TYPE
 ;

datatype
 : iri
 ;



numericLiteral
 : INTEGER
 | DECIMAL
 | DOUBLE
 ;

rdfLiteral
 : string (LANGTAG | '^^' datatype)?
 ;

booleanLiteral
 : KW_TRUE
 | KW_FALSE
 ;

string
 : STRING_LITERAL_LONG1
 | STRING_LITERAL_LONG2
 | STRING_LITERAL1
 | STRING_LITERAL2
 ;

// Keywords
KW_ABSTRACT
 : A B S T R A C T
 ;

KW_AS
 : A S
 ;

KW_BASE
 : B A S E
 ;

KW_EXTENDS
 : E X T E N D S
 ;

KW_IMPORT
 : I M P O R T
 ;

KW_RESTRICTS
 : R E S T R I C T S
 ;

KW_EXTERNAL
 : E X T E R N A L
 ;

KW_START
 : S T A R T
 ;

KW_VIRTUAL
 : V I R T U A L
 ;


KW_CLOSED
 : C L O S E D
 ;

KW_EXTRA
 : E X T R A
 ;

KW_LITERAL
 : L I T E R A L
 ;

KW_IRI
 : I R I
 ;

KW_NONLITERAL
 : N O N L I T E R A L
 ;

KW_BNODE
 : B N O D E
 ;

KW_AND
 : A N D
 ;

KW_OR
 : O R
 ;

KW_MININCLUSIVE
 : M I N I N C L U S I V E
 ;

KW_MINEXCLUSIVE
 : M I N E X C L U S I V E
 ;

KW_MAXINCLUSIVE
 : M A X I N C L U S I V E
 ;

KW_MAXEXCLUSIVE
 : M A X E X C L U S I V E
 ;

KW_LENGTH
 : L E N G T H
 ;

KW_MINLENGTH
 : M I N L E N G T H
 ;

KW_MAXLENGTH
 : M A X L E N G T H
 ;

KW_TOTALDIGITS
 : T O T A L D I G I T S
 ;

KW_FRACTIONDIGITS
 : F R A C T I O N D I G I T S
 ;

KW_NOT
 : N O T
 ;

KW_TRUE
 : 'true'
 ;

KW_FALSE
 : 'false'
 ;

 // --------------------------
 // TERMINALS
 // --------------------------

// Skip white spaces in the shEx and comments.
SKIP_
 : (WHITE_SPACE | COMMENT) -> skip
 ;

fragment COMMENT
 : ('#' ~[\r\n]* | '/*' (~[*] | '*' ('\\/' | ~[/]))* '*/') 
 ;

// A white space is defined as '\t' or '\r' or '\n'.
fragment WHITE_SPACE
 : [ \t\r\n]+
 ;

CODE
 : '{' (~[%\\] | '\\' [%\\] | UCHAR)* '%' '}'
 ;

/*
VAR
 : /* VAR1
 | VAR2
 ;
*/

/*
VAR1
 : '$' VARNAME
 ;
*/

/*
VAR2
 : '?' VARNAME
 ;
*/

RDF_TYPE
 : 'a'
 ;

IRIREF
 : '<' (~[\u0000-\u0020=<>"{}|^`\\] | UCHAR)* '>'
 ; /* #x00=NULL #01-#x1F=control codes #x20=space */

PNAME_NS
 : PN_PREFIX? ':'
 ;

PNAME_LN
 : PNAME_NS PN_LOCAL
 ;

ATPNAME_NS
 : '@' PN_PREFIX? ':'
 ;

ATPNAME_LN
 : '@' PNAME_NS PN_LOCAL
 ;

REGEXP
 : '/' (~[/\n\r\\] | '\\' [/nrt\\|.?*+(){}[\]$^-] | UCHAR)+ '/'
 ;

REGEXP_FLAGS
 : [smix]+
 ;

BLANK_NODE_LABEL
 : '_:' (PN_CHARS_U | [0-9]) ((PN_CHARS | '.')* PN_CHARS)?
 ;

LANGTAG
 : '@' [a-zA-Z]+ ('-' [a-zA-Z0-9]+)*
 ;

INTEGER
 : [+-]? [0-9]+
 ;

DECIMAL
 : [+-]? [0-9]* '.' [0-9]+
 ;

DOUBLE
 : [+-]? ([0-9]+ '.' [0-9]* EXPONENT | '.'? [0-9]+ EXPONENT)
 ;

STEM_MARK
 : '~'
 ;

UNBOUNDED
 : '*'
 ;

fragment EXPONENT
 : [eE] [+-]? [0-9]+
 ;

STRING_LITERAL1
 : '\'' (~[\u0027\u005C\u000A\u000D] | ECHAR | UCHAR)* '\''
 ; /* #x27=' #x5C=\ #xA=new line #xD=carriage return */

STRING_LITERAL2
 : '"' (~[\u0022\u005C\u000A\u000D] | ECHAR | UCHAR)* '"'
 ;   /* #x22=" #x5C=\ #xA=new line #xD=carriage return */

STRING_LITERAL_LONG1
 : '\'\'\'' (('\'' | '\'\'')? (~['\\] | ECHAR | UCHAR))* '\'\'\''
 ;

STRING_LITERAL_LONG2
 : '"""' (('"' | '""')? (~["\\] | ECHAR | UCHAR))* '"""'
 ;

fragment UCHAR
 : '\\u' HEX HEX HEX HEX
 | '\\U' HEX HEX HEX HEX HEX HEX HEX HEX
 ;

fragment ECHAR
 : '\\' [tbnrf\\"']
 ;

fragment PN_CHARS_BASE
 : [A-Z]
 | [a-z]
 | [\u00C0-\u00D6]
 | [\u00D8-\u00F6]
 | [\u00F8-\u02FF]
 | [\u0370-\u037D]
 | [\u037F-\u1FFF]
 | [\u200C-\u200D]
 | [\u2070-\u218F]
 | [\u2C00-\u2FEF]
 | [\u3001-\uD7FF]
 | [\uF900-\uFDCF]
 | [\uFDF0-\uFFFD]
 | [\u{10000}-\u{EFFFD}]
 // | [\uD800-\uDB7F] [\uDC00-\uDFFF]
 ;

fragment PN_CHARS_U
 : PN_CHARS_BASE
 | '_'
 ;

fragment PN_CHARS
 : PN_CHARS_U
 | '-'
 | [0-9]
 | [\u00B7]
 | [\u0300-\u036F]
 | [\u203F-\u2040]
 ;

fragment PN_PREFIX
 : PN_CHARS_BASE ((PN_CHARS | '.')* PN_CHARS)?
 ;

fragment PN_LOCAL
 : (PN_CHARS_U | ':' | [0-9] | PLX) ((PN_CHARS | '.' | ':' | PLX)* (PN_CHARS | ':' | PLX))?
 ;

fragment PLX
 : PERCENT
 | PN_LOCAL_ESC
 ;

fragment PERCENT
 : '%' HEX HEX
 ;

fragment HEX
 : [0-9]
 | [A-F]
 | [a-f]
 ;

fragment PN_LOCAL_ESC
 : '\\' ('_' | '~' | '.' | '-' | '!' | '$' | '&' | '\'' | '(' | ')' | '*' | '+' | ',' | ';' | '=' | '/' | '?' | '#' | '@' | '%')
 ;

/*
VARNAME
 : ( PN_CHARS_U | DIGIT ) ( PN_CHARS_U | DIGIT | '\u00B7' | ('\u0300'..'\u036F') | ('\u203F'..'\u2040') )*
 ;
*/

/* fragment DIGIT: '0'..'9' ; */
fragment A:('a'|'A');
fragment B:('b'|'B');
fragment C:('c'|'C');
fragment D:('d'|'D');
fragment E:('e'|'E');
fragment F:('f'|'F');
fragment G:('g'|'G');
fragment H:('h'|'H');
fragment I:('i'|'I');
fragment J:('j'|'J');
fragment K:('k'|'K');
fragment L:('l'|'L');
fragment M:('m'|'M');
fragment N:('n'|'N');
fragment O:('o'|'O');
fragment P:('p'|'P');
fragment Q:('q'|'Q');
fragment R:('r'|'R');
fragment S:('s'|'S');
fragment T:('t'|'T');
fragment U:('u'|'U');
fragment V:('v'|'V');
fragment W:('w'|'W');
fragment X:('x'|'X');
fragment Y:('y'|'Y');
fragment Z:('z'|'Z');
