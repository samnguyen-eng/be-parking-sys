package com.parking.api.service;

import com.parking.api.dto.request.DepositRequest;
import com.parking.api.dto.response.BalanceResponse;
import com.parking.api.dto.response.DepositResponse;
import com.parking.api.entity.Account;
import com.parking.api.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    private final com.parking.api.repository.AccountRepository accountRepository;

    @Transactional
    public DepositResponse deposit(Long userId, DepositRequest request) {
        Account account = accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found for userId: " + userId));

        account.setBalance(account.getBalance().add(request.getAmount()));
        account = accountRepository.save(account);

        log.info("Deposited {} for userId={}. New balance: {}", request.getAmount(), userId, account.getBalance());

        return DepositResponse.builder()
                .accountId(account.getId())
                .balance(account.getBalance())
                .depositAmount(request.getAmount())
                .message("Deposit successful. New balance: $" + account.getBalance())
                .build();
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(Long userId) {
        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found for userId: " + userId));

        return BalanceResponse.builder()
                .accountId(account.getId())
                .balance(account.getBalance())
                .build();
    }
}
