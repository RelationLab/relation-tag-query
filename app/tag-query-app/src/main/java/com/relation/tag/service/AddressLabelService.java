package com.relation.tag.service;

import com.relation.tag.entity.opensearch.AddressLabel;
import com.relation.tag.entity.postgresql.Contract;
import com.relation.tag.entity.postgresql.Label;
import com.relation.tag.entity.postgresql.UserInfo;
import com.relation.tag.enums.GetModeEnum;
import com.relation.tag.enums.ResponseCodeEnum;
import com.relation.tag.mapper.primary.ContractMapper;
import com.relation.tag.mapper.primary.LabelMapper;
import com.relation.tag.mapper.primary.UserInfoMapper;
import com.relation.tag.repository.AddressLabelRepository;
import com.relation.tag.request.GetAddressLabelRequest;
import com.relation.tag.request.GetAddressLabelsRequest;
import com.relation.tag.request.Page;
import com.relation.tag.response.GetAddressLabelsResponse;
import com.relation.tag.vo.AddressInfo;
import com.relation.tag.vo.LabelInfo;
import com.relation.tag.vo.Labels;
import org.apache.lucene.search.join.ScoreMode;
import org.assertj.core.util.Lists;
import org.assertj.core.util.Sets;
import org.opensearch.data.client.orhlc.NativeSearchQueryBuilder;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.opensearch.index.query.QueryBuilders.nestedQuery;
import static org.opensearch.index.query.QueryBuilders.termQuery;

@Service
public class AddressLabelService {
    @Autowired
    private AddressLabelRepository repository;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private LabelMapper labelMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;


    public List<GetAddressLabelsResponse> findByAddress(GetAddressLabelRequest request) {
        AddressLabel addressLabel = repository.findByAddress(request.getInput().getAddress());
        if (addressLabel == null) {
            return null;
        }

        UserInfo userInfo = null;//readOnlyUserInfoService.getByAddress(AddressLabelVO.getAddress());
        Integer count = null;//contractService.selectCountByContractAddress(AddressLabelVO.getAddress());
        boolean isContract = count > 0 ? true : false;
        List<String> labelNames = CollectionUtils.isEmpty(addressLabel.getLabels()) ? null : addressLabel.getLabels().stream().map(Labels::getName).collect(Collectors.toList());
//        List<Label> labelList = readOnlyLabelService.selectsByName(labelNames);
//        List<LabelInfo> labelInfos = labelList.stream().map(item -> LabelInfo.builder()
//                .content(item.getContent())
//                .name(item.getName())
//                .source(item.getSource())
//                .build()).collect(Collectors.toList());
//
//        GetAddressLabelsResponse getAddressLabelsResponse = GetAddressLabelsResponse.builder()
//                .address(AddressInfo.builder()
//                        .value(AddressLabelVO.getAddress())
//                        .isContract(isContract)
//                        .build())
//                .labels(labelInfos)
//                .build();
        return Lists.newArrayList(GetAddressLabelsResponse.builder()
                .userName(Objects.nonNull(userInfo) ? userInfo.getName() : null)
                .userIntroduction(Objects.nonNull(userInfo) ? userInfo.getIntroduction() : null)
                .userTwitter(Objects.nonNull(userInfo) ? userInfo.getTwitter() : null)
                .build());
    }


    private void buildPage(Query searchQuery, Page page) {
        searchQuery.setPageable(PageRequest.of(page.getPage(), page.getPageSize(), Sort.by(Sort.Order.asc("address"))));
    }

    private Query getNestedQuery(List<String> labels) {
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        labels.forEach(item -> {
            queryBuilder.should(nestedQuery(
                    "labels",
                    termQuery("labels.name", item),
                    ScoreMode.None));
        });
        return new NativeSearchQueryBuilder()
                .withQuery(queryBuilder)
                .build();
    }

    private GetAddressLabelsResponse buildGetAddressLabelsResponse(AddressLabel addressLabel, Map<String, Contract> contractMap, Map<String, Label> labelNamesMap, Map<String, UserInfo> userInfoMap) {
        List<LabelInfo> labelInfos = Lists.newArrayList();
        buildLabelInfos(labelInfos, addressLabel, labelNamesMap);
        String address = addressLabel.getAddress();
        UserInfo userInfo = userInfoMap.get(address);
        Contract contract = contractMap.get(address);
        boolean isContract = Objects.nonNull(contract);
        return GetAddressLabelsResponse.builder()
                .address(AddressInfo.builder()
                        .value(address)
                        .isContract(isContract)
                        .build())
                .labels(labelInfos)
                .userTwitter(Objects.nonNull(userInfo) ? userInfo.getTwitter() : null)
                .userIntroduction(Objects.nonNull(userInfo) ? userInfo.getIntroduction() : null)
                .userName(Objects.nonNull(userInfo) ? userInfo.getName() : null)
                .build();
    }

    private void buildLabelInfos(List<LabelInfo> labelInfos, AddressLabel addressLabel, Map<String, Label> labelNamesMap) {
        if (CollectionUtils.isEmpty(addressLabel.getLabels())) {
            return;
        }
        addressLabel.getLabels().forEach(label -> {
            String labelName = label.getName();
            Label labelItem = labelNamesMap.get(labelName);
            labelInfos.add(LabelInfo.builder()
                    .name(labelName)
                    .content(labelItem != null ? labelItem.getContent() : null)
                    .source(labelItem != null ? labelItem.getSource() : null)
                    .build());
        });
    }

    public List<GetAddressLabelsResponse> getAddressLabels(GetAddressLabelsRequest request) {
        GetAddressLabelsRequest.Input input = request.getInput();
        List<String> inputLabels = input.getLabels();
        List<Label> labels = labelMapper.selectsByName(inputLabels);
        if (CollectionUtils.isEmpty(labels) || labels.size() != inputLabels.size()) {
            throw new RuntimeException(ResponseCodeEnum.ERROR_NOT_FOUNT_LABEL.errorMessage(), null);
        }
        Set<String> labelTypes = labels.stream().map(Label::getType).collect(Collectors.toSet());
        if (labelTypes.size() < labels.size()
                && GetModeEnum.valueOf(request.getInput().getMode()) == GetModeEnum.PRECISION) {
            return new ArrayList<>();
        }
        List<AddressLabel> addressLabels = findByLabels(input);
        List<String> addressList = addressLabels.stream().map(AddressLabel::getAddress).collect(Collectors.toList());
        List<Contract> contracts = contractMapper.selectByContractAddresses(addressList);
        Map<String, Contract> contractMap = contracts.stream().collect(Collectors.toMap(Contract::getContractAddress, Function.identity()));
        Set<String> labelNames = Sets.newHashSet();
        addressLabels.forEach(item -> {
            labelNames.addAll(item.getLabels().stream().map(Labels::getName).collect(Collectors.toSet()));
        });
        List<Label> labelList = labelMapper.selectsByName(labelNames);
        Map<String, Label> labelNamesMap = labelList.stream().collect(Collectors.toMap(Label::getName, Function.identity()));
        List<UserInfo> userInfos = userInfoMapper.getByAddress(addressList);
        Map<String, UserInfo> userInfoMap = userInfos.stream().collect(Collectors.toMap(UserInfo::getAddress, Function.identity()));

        return addressLabels.stream().map(item -> {
            return buildGetAddressLabelsResponse(item, contractMap, labelNamesMap, userInfoMap);
        }).collect(Collectors.toList());
    }

    private List<AddressLabel> findByLabels(GetAddressLabelsRequest.Input input) {
        Query searchQuery = getNestedQuery(input.getLabels());
        buildPage(searchQuery, Page.builder().page(input.getBaseId().intValue()).pageSize(input.getLimit()).build());
        SearchHits<AddressLabel> addressLabels = elasticsearchOperations.search(searchQuery, AddressLabel.class);
        if (addressLabels == null) {
            return new ArrayList<>();
        }
        return addressLabels.stream().map(SearchHit::getContent).collect(Collectors.toList());
    }
}
