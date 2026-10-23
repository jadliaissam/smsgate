// Package sms provides allocation-free E.164 validation and SMS segment counting.
package sms

import "unicode/utf16"

// asciiCost holds the GSM 03.38 septet cost of each ASCII byte:
// 0 = not encodable in GSM-7, 1 = basic set, 2 = extension table (ESC + char).
var asciiCost = func() (t [128]uint8) {
	const basic = "@$\n\r !\"#%&'()*+,-./0123456789:;<=>?ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"
	for i := 0; i < len(basic); i++ {
		t[basic[i]] = 1
	}
	const ext = "\f^{}\\[~]|"
	for i := 0; i < len(ext); i++ {
		t[ext[i]] = 2
	}
	return t
}()

// gsmCost returns the septet cost of r, or 0 if r is not in GSM 03.38.
func gsmCost(r rune) int {
	if r < 128 {
		return int(asciiCost[r])
	}
	switch r {
	case '€':
		return 2
	case '£', '¥', 'è', 'é', 'ù', 'ì', 'ò', 'Ç', 'Ø', 'ø', 'Å', 'å',
		'Δ', 'Φ', 'Γ', 'Λ', 'Ω', 'Π', 'Ψ', 'Σ', 'Θ', 'Ξ',
		'Æ', 'æ', 'ß', 'É', '¤', '¡', 'Ä', 'Ö', 'Ñ', 'Ü', '§', '¿',
		'ä', 'ö', 'ñ', 'ü', 'à':
		return 1
	}
	return 0
}

// Segments returns the number of SMS segments body needs and whether it must be
// sent as UCS-2. GSM-7: 160 single / 153 per part. UCS-2 (UTF-16 code units):
// 70 single / 67 per part. Concatenated parts are filled character by
// character, so an extension-table ESC pair (GSM-7) or a surrogate pair
// (UCS-2) that does not fit the current part starts the next one, as phones
// split them. An empty body yields 0 segments. Single pass, no allocations.
func Segments(body string) (segments int, ucs2 bool) {
	if body == "" {
		return 0, false
	}
	const gsmSingle, gsmPart, ucsSingle, ucsPart = 160, 153, 70, 67
	var (
		septets, units    int // totals (single-part check)
		gsmParts, gsmFill = 1, 0
		ucsParts, ucsFill = 1, 0
		gsm               = true
	)
	for _, r := range body {
		if gsm {
			if c := gsmCost(r); c != 0 {
				septets += c
				if gsmFill+c > gsmPart {
					gsmParts++
					gsmFill = 0
				}
				gsmFill += c
			} else {
				gsm = false
			}
		}
		u := utf16.RuneLen(r)
		units += u
		if ucsFill+u > ucsPart {
			ucsParts++
			ucsFill = 0
		}
		ucsFill += u
	}
	if gsm {
		if septets <= gsmSingle {
			return 1, false
		}
		return gsmParts, false
	}
	if units <= ucsSingle {
		return 1, true
	}
	return ucsParts, true
}

// ValidE164 reports whether s matches ^\+[1-9][0-9]{6,14}$.
func ValidE164(s string) bool {
	if len(s) < 8 || len(s) > 16 || s[0] != '+' || s[1] < '1' || s[1] > '9' {
		return false
	}
	for i := 2; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}
