package sms

import (
	"strings"
	"testing"
)

func TestValidE164(t *testing.T) {
	tests := []struct {
		in   string
		want bool
	}{
		{"+37061234567", true},
		{"+1234567", true},
		{"+123456789012345", true},
		{"37061234567", false},
		{"+0123456789", false},
		{"+1234567890123456", false},
		{"+123456", false},
		{"+12 345", false},
		{"+1234567a", false},
		{"+", false},
		{"", false},
		{"+37061234567\n", false},
	}
	for _, tc := range tests {
		if got := ValidE164(tc.in); got != tc.want {
			t.Errorf("ValidE164(%q) = %v, want %v", tc.in, got, tc.want)
		}
	}
}

func TestSegments(t *testing.T) {
	r := strings.Repeat
	tests := []struct {
		name string
		body string
		seg  int
		ucs2 bool
	}{
		{"empty", "", 0, false},
		{"160a", r("a", 160), 1, false},
		{"161a", r("a", 161), 2, false},
		{"306a", r("a", 306), 2, false},
		{"307a", r("a", 307), 3, false},
		{"459a", r("a", 459), 3, false},
		{"460a", r("a", 460), 4, false},
		{"80euro", r("€", 80), 1, false},
		{"81euro", r("€", 81), 2, false},
		{"ext chars", "^{}\\[~]|", 1, false},
		{"form feed", "\f", 1, false},
		{"gsm non-ascii", "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ¤¡ÄÖÑÜ§¿äöñüà", 1, false},
		{"backtick is ucs2", "`", 1, true},
		{"70ą", r("ą", 70), 1, true},
		{"71ą", r("ą", 71), 2, true},
		{"134ą", r("ą", 134), 2, true},
		{"135ą", r("ą", 135), 3, true},
		{"35emoji", r("😀", 35), 1, true},
		{"36emoji", r("😀", 36), 2, true},
		{"101emoji", r("😀", 101), 4, true},
		{"mixed", r("a", 100) + "ą", 2, true},
		{"control char is ucs2", "\x01", 1, true},
	}
	for _, tc := range tests {
		seg, ucs2 := Segments(tc.body)
		if seg != tc.seg || ucs2 != tc.ucs2 {
			t.Errorf("%s: Segments = (%d,%v), want (%d,%v)", tc.name, seg, ucs2, tc.seg, tc.ucs2)
		}
	}
}

var sink int

func BenchmarkSegments(b *testing.B) {
	bodies := map[string]string{
		"gsm":  strings.Repeat("Hello wörld €", 20),
		"ucs2": strings.Repeat("Labas ąčę 😀", 20),
	}
	for name, body := range bodies {
		b.Run(name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				sink, _ = Segments(body)
			}
		})
	}
}

func BenchmarkValidE164(b *testing.B) {
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = ValidE164("+37061234567")
	}
}
