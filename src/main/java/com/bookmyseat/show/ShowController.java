package com.bookmyseat.show;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /shows} — ADMIN only (enforced by the security filter chain).
 * The caller identity is not needed here; creation is not attributed.
 */
@RestController
@RequestMapping("/shows")
public class ShowController {

	private final ShowService shows;

	public ShowController(ShowService shows) {
		this.shows = shows;
	}

	@PostMapping
	public ResponseEntity<ShowResponse> create(@RequestBody(required = false) CreateShowRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(shows.createShow(request));
	}
}
