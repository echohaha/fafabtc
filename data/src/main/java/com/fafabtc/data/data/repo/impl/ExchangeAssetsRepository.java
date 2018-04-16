package com.fafabtc.data.data.repo.impl;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.util.Pair;

import com.fafabtc.common.file.AndroidAssetsUtils;
import com.fafabtc.common.file.FileUtils;
import com.fafabtc.common.json.GsonHelper;
import com.fafabtc.data.consts.DataBroadcasts;
import com.fafabtc.data.data.global.AssetsStateRepository;
import com.fafabtc.data.data.repo.PortfolioRepo;
import com.fafabtc.data.data.repo.BlockchainAssetsRepo;
import com.fafabtc.data.data.repo.ExchangeAssetsRepo;
import com.fafabtc.data.data.repo.ExchangeRepo;
import com.fafabtc.data.data.repo.PairRepo;
import com.fafabtc.data.data.repo.TickerRepo;
import com.fafabtc.data.model.entity.exchange.Portfolio;
import com.fafabtc.data.model.entity.exchange.BlockchainAssets;
import com.fafabtc.data.model.entity.exchange.Exchange;
import com.fafabtc.data.model.vo.ExchangeAssets;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.SingleTransformer;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

import static com.fafabtc.data.provider.Providers.ASSETS_DATA_FILE;

/**
 * The repository of exchange assets.
 * <p>
 * To separate the initialization of different exchange。
 * <p>
 * Created by jastrelax on 2018/1/8.
 */
@Singleton
public class ExchangeAssetsRepository implements ExchangeAssetsRepo {

    @Inject
    BlockchainAssetsRepo blockchainAssetsRepo;

    @Inject
    PortfolioRepo portfolioRepo;

    @Inject
    ExchangeRepo exchangeRepo;

    @Inject
    AssetsStateRepository assetsStateRepository;

    @Inject
    TickerRepo tickerRepo;

    @Inject
    Context context;

    @Inject
    PairRepo pairRepo;

    @Inject
    public ExchangeAssetsRepository() {
    }

    /**
     * Initiate all exchanges and their assets.
     *
     * @return a Completable
     */
    @Override
    public Completable init() {
        return Observable.fromArray(ExchangeRepo.EXCHANGES)
                .flatMapCompletable(new Function<String, CompletableSource>() {
                    @Override
                    public CompletableSource apply(String s) throws Exception {
                        return initExchange(s).subscribeOn(Schedulers.io());
                    }
                }, true);
    }

    /**
     * Initiate a exchange and it's assets.
     *
     * @param exchangeName an exchange name
     * @return a Completable
     */
    @Override
    public Completable initExchange(final String exchangeName) {
        Completable initExchange = exchangeRepo.init(exchangeName)
                .doOnSubscribe(new Consumer<Disposable>() {
                    @Override
                    public void accept(Disposable disposable) throws Exception {
                        sendExchangeBroadcast(exchangeName, DataBroadcasts.Actions.ACTION_INITIATE_EXCHANGE, null);
                    }
                });
        Completable initExchangeAssets = initExchangeAssets(exchangeName)
                .doOnSubscribe(new Consumer<Disposable>() {
                    @Override
                    public void accept(Disposable disposable) throws Exception {
                        sendExchangeBroadcast(exchangeName, DataBroadcasts.Actions.ACTION_INITIATE_ASSETS, null);
                    }
                })
                .concatWith(assetsStateRepository.setAssetsInitialized(exchangeName, true));

        return initExchange
                .concatWith(initExchangeAssets)
                .doOnComplete(new Action() {
                    @Override
                    public void run() throws Exception {
                        sendExchangeBroadcast(exchangeName, DataBroadcasts.Actions.ACTION_DATA_INITIALIZED, null);
                    }
                })
                .concatWith(tickerRepo.getLatestTickers(exchangeName, new Date()).toCompletable());
    }

    /**
     * Initiate assets of a exchange.
     * <p>
     * Every blockchain assets belong to a {@link Portfolio} and a {@link Exchange},
     * If assets of a exchange is already initiated, which means the blockchain assets of
     * all assets account of an exchange have been initiated.
     * <p>
     * If assets of an exchange is already initialized, initiate it again in case of outdated
     * or missed blockchain assets item. Otherwise initiate it and restore the blockchain assets
     * from file with default or saved assets.
     *
     * @param exchangeName Name of exchange to be initiated.
     * @return a Completable
     * @see #doInitExchangeAssets(String)
     */
    @Override
    public Completable initExchangeAssets(final String exchangeName) {
        return isExchangeAssetsInitialized(exchangeName)
                .flatMapCompletable(new Function<Boolean, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Boolean isInitialized) throws Exception {
                        if (isInitialized) {
                            return doInitExchangeAssets(exchangeName);
                        } else {
                            return doInitExchangeAssets(exchangeName)
                                    .concatWith(restoreExchangeAssetsFromFile(exchangeName));
                        }
                    }
                });
    }

    /**
     * Tell whether the blockchain assets of a exchange has been initialized, which
     * means all base blockchain assets of the exchange pairs is initialized.
     *
     * @param exchange the exchange name
     * @return a Single
     */
    @Override
    public Single<Boolean> isExchangeAssetsInitialized(final String exchange) {
        final SingleTransformer<List<Portfolio>, Boolean> transformer = new SingleTransformer<List<Portfolio>, Boolean>() {
            @Override
            public SingleSource<Boolean> apply(Single<List<Portfolio>> upstream) {
                return upstream
                        .flattenAsObservable(new Function<List<Portfolio>, Iterable<Portfolio>>() {
                            @Override
                            public Iterable<Portfolio> apply(List<Portfolio> portfolioList) throws Exception {
                                return portfolioList;
                            }
                        })
                        .zipWith(exchangeRepo.getExchange(exchange).toObservable(),
                                new BiFunction<Portfolio, Exchange, Pair<Portfolio, Exchange>>() {
                                    @Override
                                    public Pair<Portfolio, Exchange> apply(Portfolio portfolio, Exchange exchange) {
                                        return new Pair<>(portfolio, exchange);
                                    }
                                })
                        .flatMapSingle(new Function<Pair<Portfolio, Exchange>, SingleSource<ExchangeAssets>>() {
                            @Override
                            public SingleSource<ExchangeAssets> apply(Pair<Portfolio, Exchange> portfolioExchangePair) throws Exception {
                                return getExchangeAssets(portfolioExchangePair.first, portfolioExchangePair.second);
                            }
                        })
                        .zipWith(pairRepo.baseCount(exchange).toObservable(),
                                new BiFunction<ExchangeAssets, Integer, Boolean>() {
                                    @Override
                                    public Boolean apply(ExchangeAssets exchangeAssets, Integer integer) throws Exception {
                                        return exchangeAssets.getBlockchainAssetsList().size() > 0 && exchangeAssets.getBlockchainAssetsList().size() == integer;
                                    }
                                })
                        .toList()
                        .map(new Function<List<Boolean>, Boolean>() {
                            @Override
                            public Boolean apply(List<Boolean> booleans) throws Exception {
                                for (Boolean initialized : booleans) {
                                    if (!initialized)
                                        return false;
                                }
                                return true;
                            }
                        });
            }
        };
        return portfolioRepo.getAll()
                .flatMap(new Function<List<Portfolio>, SingleSource<? extends Boolean>>() {
                    @Override
                    public SingleSource<? extends Boolean> apply(List<Portfolio> portfolioList) throws Exception {
                        if (portfolioList.isEmpty())
                            return Single.just(false);
                        return Single.just(portfolioList).compose(transformer);
                    }
                });
    }

    /**
     * Restore assets from saved files or assets files.
     * <p>
     * <strong>Restored blockchain assets may be outdated and not consistent with latest data.</strong>
     * <p>
     * Firstly, try to restore assets from files in local storage, if not exist, then try restore
     * from assets data file packaged in the APK.
     *
     * @param exchangeName name of an exchange
     * @return a Completable
     */
    private Completable restoreExchangeAssetsFromFile(final String exchangeName) {
        final String fileName = exchangeName.toLowerCase() + ASSETS_DATA_FILE;
        return Observable
                .fromCallable(new Callable<ExchangeAssets[]>() {
                    @Override
                    public ExchangeAssets[] call() throws Exception {
                        return GsonHelper.gson().fromJson(FileUtils.readFile(fileName), ExchangeAssets[].class);
                    }
                })
                .onErrorReturn(new Function<Throwable, ExchangeAssets[]>() {
                    @Override
                    public ExchangeAssets[] apply(Throwable throwable) throws Exception {
                        return GsonHelper.gson().fromJson(AndroidAssetsUtils.readFromAssets(context.getAssets(), fileName), ExchangeAssets[].class);
                    }
                })
                .flatMapIterable(new Function<ExchangeAssets[], Iterable<ExchangeAssets>>() {
                    @Override
                    public Iterable<ExchangeAssets> apply(ExchangeAssets[] exchangeAssets) throws Exception {
                        return Arrays.asList(exchangeAssets);
                    }
                })
                .flatMapCompletable(new Function<ExchangeAssets, CompletableSource>() {
                    @Override
                    public CompletableSource apply(final ExchangeAssets exchangeAssets) throws Exception {
                        return restoreExchangeAssets(exchangeAssets);
                    }
                });
    }

    /**
     * Restore an exchange assets.
     *
     * @param exchangeAssets the exchangeAssets to be restored.
     * @return a Completable
     */
    private Completable restoreExchangeAssets(final ExchangeAssets exchangeAssets) {
        final String portfolioName = exchangeAssets.getPortfolio().getName();
        Single<Portfolio> portfolioSingle = portfolioRepo.getByName(portfolioName)
                .onErrorResumeNext(createPortfolioOfExchange(portfolioName, exchangeAssets.getExchange().getName()));
        return portfolioSingle
                .flatMapCompletable(new Function<Portfolio, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Portfolio portfolio) throws Exception {
                        exchangeAssets.setPortfolio(portfolio);
                        return blockchainAssetsRepo.restoreExchangeBlockchainAssets(exchangeAssets);
                    }
                })
                .onErrorComplete();
    }

    /**
     * Initiate account assets of an exchange.
     * <p>
     * Every blockchain assets belong to a {@link Portfolio} and a {@link Exchange},
     * If assets of a exchange is already initiated, which means the blockchain assets of
     * all assets account of an exchange have been initiated.
     * <p>
     * If no account assets exist then create a new one with a default name.
     * Otherwise initiate all the account assets of specified exchange.
     *
     * @param exchange the exchange whose assets to be initialized
     * @return a Completable
     */
    private Completable doInitExchangeAssets(final String exchange) {
        return portfolioRepo.getAll()
                .flatMapCompletable(new Function<List<Portfolio>, CompletableSource>() {
                    @Override
                    public CompletableSource apply(List<Portfolio> portfolioList) throws Exception {
                        if (portfolioList.isEmpty()) {
                            return createPortfolioOfExchange(Portfolio.DEFAULT_NAME, exchange).toCompletable();
                        } else {
                            return Observable.fromIterable(portfolioList)
                                    .flatMapCompletable(new Function<Portfolio, CompletableSource>() {
                                        @Override
                                        public CompletableSource apply(Portfolio portfolio) throws Exception {
                                            return initPortfoliosOfExchange(portfolio, exchange).toCompletable();
                                        }
                                    });
                        }
                    }
                });
    }


    /**
     * Create account assets which belongs to an exchange.
     * <p>
     * When creating a account assets, set it as the current active account.
     * Then initiate it.
     * <p>
     * If the account assets already existed, no need to save it again. If current
     * account assets is not the account assets to be created, update it's state to
     * {@link Portfolio.State#ACTIVE}.
     *
     * @param assetsName name of the account to be created
     * @param exchange   name of the exchange
     * @return a Single
     * @see #initPortfoliosOfExchange(Portfolio, String)
     */
    @Override
    public Single<Portfolio> createPortfolioOfExchange(final String assetsName, final String exchange) {
        return portfolioRepo.create(assetsName)
                .flatMap(new Function<Portfolio, SingleSource<Portfolio>>() {
                    @Override
                    public SingleSource<Portfolio> apply(Portfolio portfolio) throws Exception {
                        return initPortfoliosOfExchange(portfolio, exchange);
                    }
                });
    }

    /**
     * Create a new account assets and initiate all exchange assets which
     * belong to it.
     *
     * @param assetsName name of the account assets to be created.
     * @return a Single
     */
    @Override
    public Single<Portfolio> createPortfolioOfExchange(final String assetsName) {
        return exchangeRepo.getExchanges()
                .flattenAsObservable(new Function<Exchange[], Iterable<Exchange>>() {
                    @Override
                    public Iterable<Exchange> apply(Exchange[] exchanges) throws Exception {
                        return Arrays.asList(exchanges);
                    }
                })
                .flatMapSingle(new Function<Exchange, SingleSource<Portfolio>>() {
                    @Override
                    public SingleSource<Portfolio> apply(Exchange exchange) throws Exception {
                        return createPortfolioOfExchange(assetsName, exchange.getName());
                    }
                })
                .firstOrError();
    }

    /**
     * Initiate block chain assets of an account assets which belong to an exchange.
     *
     * @param portfolio the account assets to be initialized
     * @param exchangeName  the name of the exchange
     * @return a Completable
     */
    private Single<Portfolio> initPortfoliosOfExchange(final Portfolio portfolio, final String exchangeName) {
        return exchangeRepo.getExchange(exchangeName)
                .flatMapCompletable(new Function<Exchange, CompletableSource>() {
                    @Override
                    public CompletableSource apply(Exchange exchange) throws Exception {
                        ExchangeAssets exchangeAssets = new ExchangeAssets();
                        exchangeAssets.setPortfolio(portfolio);
                        exchangeAssets.setExchange(exchange);
                        Portfolio portfolio = exchangeAssets.getPortfolio();
                        return blockchainAssetsRepo.initExchangeBlockchainAssets(portfolio.getUuid(), exchange.getName());
                    }
                }).toSingleDefault(portfolio);
    }

    /**
     * Get all exchange assets which belong to an account assets.
     *
     * @param portfolio the account assets
     * @return a Single
     */
    @Override
    public Single<List<ExchangeAssets>> getAllExchangeAssetsOfAccount(Portfolio portfolio) {
        return Single.just(portfolio)
                .flatMapObservable(new Function<Portfolio, ObservableSource<Pair<Portfolio, Exchange>>>() {
                    @Override
                    public ObservableSource<Pair<Portfolio, Exchange>> apply(final Portfolio portfolio) throws Exception {
                        return exchangeRepo.getExchanges()
                                .flattenAsObservable(new Function<Exchange[], Iterable<Exchange>>() {
                                    @Override
                                    public Iterable<Exchange> apply(Exchange[] exchanges) throws Exception {
                                        return Arrays.asList(exchanges);
                                    }
                                })
                                .map(new Function<Exchange, Pair<Portfolio, Exchange>>() {
                                    @Override
                                    public Pair<Portfolio, Exchange> apply(Exchange exchange) throws Exception {
                                        return new Pair<>(portfolio, exchange);
                                    }
                                });
                    }
                }).flatMap(new Function<Pair<Portfolio, Exchange>, ObservableSource<ExchangeAssets>>() {
                    @Override
                    public ObservableSource<ExchangeAssets> apply(Pair<Portfolio, Exchange> portfolioExchangePair) throws Exception {
                        return getExchangeAssets(portfolioExchangePair.first, portfolioExchangePair.second).toObservable();
                    }
                }).toList();
    }

    /**
     * Get all exchange assets of an exchange.
     *
     * @param exchange the exchange
     * @return a Single
     */
    @Override
    public Single<List<ExchangeAssets>> getAllExchangeAssetsOfExchange(final Exchange exchange) {
        return portfolioRepo.getAll()
                .flattenAsObservable(new Function<List<Portfolio>, Iterable<Portfolio>>() {
                    @Override
                    public Iterable<Portfolio> apply(List<Portfolio> portfolioList) throws Exception {
                        return portfolioList;
                    }
                }).flatMap(new Function<Portfolio, ObservableSource<ExchangeAssets>>() {
                    @Override
                    public ObservableSource<ExchangeAssets> apply(Portfolio portfolio) throws Exception {
                        return getExchangeAssets(portfolio, exchange).toObservable();
                    }
                }).toList();
    }

    /**
     * Get all exchange assets of current account assets.
     *
     * @return a Single
     */
    @Override
    public Single<List<ExchangeAssets>> getAllExchangeAssetsOfCurrentAccount() {
        return portfolioRepo.getCurrent()
                .flatMap(new Function<Portfolio, SingleSource<? extends List<ExchangeAssets>>>() {
                    @Override
                    public SingleSource<? extends List<ExchangeAssets>> apply(Portfolio portfolio) throws Exception {
                        return getAllExchangeAssetsOfAccount(portfolio);
                    }
                });
    }

    /**
     * Get exchange assets of an account assets and an exchange.
     *
     * @param portfolio the account assets
     * @param exchange      the exchange
     * @return a Single
     */
    @Override
    public Single<ExchangeAssets> getExchangeAssets(Portfolio portfolio, Exchange exchange) {
        final ExchangeAssets exchangeAssets = new ExchangeAssets();
        exchangeAssets.setPortfolio(portfolio);
        exchangeAssets.setExchange(exchange);
        String assetUUID = portfolio.getUuid();
        String exchangeName = exchange.getName();
        return blockchainAssetsRepo
                .getBaseBlockchainAssets(assetUUID, exchangeName)
                .zipWith(blockchainAssetsRepo.getQuoteBlockchainAssets(assetUUID, exchangeName),
                        new BiFunction<List<BlockchainAssets>, List<BlockchainAssets>, ExchangeAssets>() {
                            @Override
                            public ExchangeAssets apply(List<BlockchainAssets> blockchainAssets,
                                                        List<BlockchainAssets> quoteBlockchainAssets) throws Exception {
                                exchangeAssets.setBlockchainAssetsList(blockchainAssets);
                                exchangeAssets.setQuoteAssetsList(quoteBlockchainAssets);
                                return exchangeAssets;
                            }
                        });
    }

    /**
     * Tell if all exchange assets have been initialized.
     *
     * @return a Single
     */
    @Override
    public Single<Boolean> isExchangeAssetsInitialized() {
        return exchangeRepo.getExchanges()
                .flattenAsObservable(new Function<Exchange[], Iterable<Exchange>>() {
                    @Override
                    public Iterable<Exchange> apply(Exchange[] exchanges) throws Exception {
                        return Arrays.asList(exchanges);
                    }
                })
                .flatMap(new Function<Exchange, ObservableSource<Boolean>>() {
                    @Override
                    public ObservableSource<Boolean> apply(Exchange exchange) throws Exception {
                        return assetsStateRepository.getAssetsInitialized(exchange.getName()).toObservable();
                    }
                })
                .reduce(new BiFunction<Boolean, Boolean, Boolean>() {
                    @Override
                    public Boolean apply(Boolean aBoolean, Boolean aBoolean2) throws Exception {
                        return aBoolean && aBoolean2;
                    }
                })
                .toSingle()
                .onErrorReturnItem(false);
    }

    @Override
    public Completable cacheExchangeAssetsToFile(final Exchange exchange) {
        return getAllExchangeAssetsOfExchange(exchange)
                .flatMapCompletable(new Function<List<ExchangeAssets>, CompletableSource>() {
                    @Override
                    public CompletableSource apply(final List<ExchangeAssets> exchangeAssets) throws Exception {
                        return Completable.fromAction(new Action() {
                            @Override
                            public void run() throws Exception {
                                if (!exchangeAssets.isEmpty()) {
                                    FileUtils.writeFile(exchange.getName() + ASSETS_DATA_FILE, GsonHelper.prettyGson().toJson(exchangeAssets));
                                }
                            }
                        });
                    }
                });
    }

    private void sendBroadcast(String action, Bundle data) {
        Intent intent = new Intent(action);
        if (data != null) {
            intent.putExtras(data);
        }
        context.sendBroadcast(intent);
    }

    private void sendExchangeBroadcast(String exchangeName, String action, Bundle extra) {
        Bundle data = new Bundle();
        if (extra != null) {
            data.putAll(extra);
        }
        data.putString(DataBroadcasts.Extras.EXTRA_EXCHANGE_NAME, exchangeName);
        sendBroadcast(action, data);
    }
}
