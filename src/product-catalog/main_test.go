// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package main

import "testing"

func TestSecondCategory(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name       string
		categories []string
		want       string
	}{
		{name: "no categories", categories: nil, want: ""},
		{name: "single category", categories: []string{"kitchen"}, want: ""},
		{name: "multiple categories", categories: []string{"kitchen", "home"}, want: "home"},
	}

	for _, tt := range tests {
		tt := tt
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			if got := secondCategory(tt.categories); got != tt.want {
				t.Fatalf("secondCategory(%v) = %q, want %q", tt.categories, got, tt.want)
			}
		})
	}
}
