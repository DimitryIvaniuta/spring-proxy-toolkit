package com.github.dimitryivaniuta.gateway.proxy.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/clients")
public class ApiClientAdminController {

    private final ApiClientRepository clientRepo;
    private final ApiClientCredentialRepository credentialRepo;
    private final ApiKeyHashService hashService;

    // ---------- DTOs ----------
    public record CreateClientRequest(
            @NotBlank @Size(max = 255) String clientName,
            @NotBlank @Size(max = 64) String clientCode
    ) {}

    public record ApiClientResponse(
            Long id,
            String clientName,
            String clientCode,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateCredentialRequest(
            @NotBlank @Size(max = 255) String credentialName
    ) {}

    public record CreateCredentialResponse(
            Long credentialId,
            Long clientId,
            String credentialName,
            boolean enabled,
            String apiKeyRaw,     // returned ONCE
            String apiKeyHash,    // stored in DB
            String policyClientKey // "apiKey:<hash>" for api_client_policy.client_key
    ) {}

    public record CredentialResponse(
            Long id,
            String credentialName,
            boolean enabled,
            String apiKeyHash,
            Instant lastUsedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    // ---------- endpoints ----------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ApiClientResponse createClient(@Valid @RequestBody CreateClientRequest req) {
        clientRepo.findByClientCode(req.clientCode()).ifPresent(x -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clientCode already exists");
        });

        ApiClient c = ApiClient.builder()
                .clientName(req.clientName())
                .clientCode(req.clientCode())
                .enabled(true)
                .build();

        ApiClient saved = clientRepo.save(c);
        return toClientResponse(saved);
    }

    @GetMapping
    public List<ApiClientResponse> listClients() {
        return clientRepo.findAll().stream().map(this::toClientResponse).toList();
    }

    @GetMapping("/{clientId}")
    public ApiClientResponse getClient(@PathVariable Long clientId) {
        ApiClient c = clientRepo.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));
        return toClientResponse(c);
    }

    @DeleteMapping("/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteClient(@PathVariable Long clientId) {
        if (!clientRepo.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found");
        }
        // DB FK has ON DELETE CASCADE; JPA also has orphanRemoval in entity mapping.
        clientRepo.deleteById(clientId);
    }

    @PostMapping("/{clientId}/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreateCredentialResponse createCredential(@PathVariable Long clientId,
                                                     @Valid @RequestBody CreateCredentialRequest req) {
        ApiClient client = clientRepo.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));

        String raw = hashService.generateRawApiKey();
        String hash = hashService.hash(raw);

        // hash uniqueness guaranteed by DB unique constraint; still handle gracefully
        ApiClientCredential cred = ApiClientCredential.builder()
                .client(client)
                .credentialName(req.credentialName())
                .apiKeyHash(hash)
                .enabled(true)
                .build();

        ApiClientCredential saved = credentialRepo.save(cred);

        return new CreateCredentialResponse(
                saved.getId(),
                client.getId(),
                saved.getCredentialName(),
                saved.isEnabled(),
                raw,
                hash,
                "apiKey:" + hash
        );
    }

    @GetMapping("/{clientId}/credentials")
    public List<CredentialResponse> listCredentials(@PathVariable Long clientId) {
        ApiClient client = clientRepo.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));

        return client.getCredentials().stream()
                .map(this::toCredentialResponse)
                .toList();
    }

    @DeleteMapping("/credentials/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteCredential(@PathVariable Long credentialId) {
        if (!credentialRepo.existsById(credentialId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found");
        }
        credentialRepo.deleteById(credentialId);
    }

    // ---------- mapping ----------
    private ApiClientResponse toClientResponse(ApiClient c) {
        return new ApiClientResponse(
                c.getId(),
                c.getClientName(),
                c.getClientCode(),
                c.isEnabled(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    private CredentialResponse toCredentialResponse(ApiClientCredential c) {
        return new CredentialResponse(
                c.getId(),
                c.getCredentialName(),
                c.isEnabled(),
                c.getApiKeyHash(),
                c.getLastUsedAt(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
