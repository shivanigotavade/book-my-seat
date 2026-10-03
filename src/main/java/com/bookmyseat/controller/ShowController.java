package com.bookmyseat.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.domain.CreateShowRequest;
import com.bookmyseat.domain.ShowDetailResponse;
import com.bookmyseat.domain.ShowResponse;
import com.bookmyseat.service.ShowService;

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

	/**
	 * Public (burst-pollable without auth): full state by default, counts only
	 * with {@code ?summary=true}.
	 */
	@GetMapping("/{id}")
	public ShowDetailResponse get(@PathVariable UUID id,
			@RequestParam(defaultValue = "false") boolean summary) {
		return shows.getShow(id, summary);
	}
}
