package fasp.nontax.service.eticket.busi.impl;

import fasp.nontax.createEiFile.CreateFile;
import fasp.nontax.createEiFile.util.NumberToCN;
import fasp.nontax.dataobjects.EInvoice.EIBillNumDTO;
import fasp.nontax.dataobjects.EInvoice.EIParamForGetPartInfoDTO;
import fasp.nontax.dataobjects.EInvoice.EInvoiceDTO;
import fasp.nontax.dataobjects.EInvoice.EInvoiceDetailDTO;
import fasp.nontax.dataobjects.dto.assist.UserDTO;
import fasp.nontax.dataobjects.dto.basedata.income.AgencyDTO;
import fasp.nontax.dataobjects.dto.basedata.income.BillTypeDTO;
import fasp.nontax.dataobjects.dto.basedata.income.IncItemDTO;
import fasp.nontax.dataobjects.dto.busi.*;
import fasp.nontax.dataobjects.dto.common.ReturnData;
import fasp.nontax.dataobjects.voucher.ETVoucherDTO;
import fasp.nontax.exception.BusiDataBaseException;
import fasp.nontax.exception.EInvoiceException;
import fasp.nontax.feign.einvocie.IEGenerateFeignClient;
import fasp.nontax.mapper.IBillAcitveMapper;
import fasp.nontax.mapper.IEticketNumMapper;
import fasp.nontax.mapper.eticket.busi.*;
import fasp.nontax.service.IOrderInnerService;
import fasp.nontax.service.busi.IChargeBillService;
import fasp.nontax.service.common.ICommonService;
import fasp.nontax.service.common.ISendMessageService;
import fasp.nontax.service.eticket.busi.*;
import fasp.nontax.service.generate.IGenerateOriSignService;
import fasp.nontax.service.generate.IGenerateVoucherService;
import fasp.nontax.service.voucher.eticket.IETVoucherService;
import fasp.nontax.utils.DoubleUtil;
import fasp.nontax.utils.EInoviceUtils;
import fasp.nontax.utils.StringUtil;
import fasp.nontax.utils.UuidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * Description:
 * User: liuxiaofei
 * Date: 2019-07-24
 */
@Service
public class ETEInvoiceService implements IETEInvoiceService{

    private Logger logger = LoggerFactory.getLogger(getClass());

    private static final String LOGGER_HEADER = "---------->[电子票据]";
    private final static String BATCH_ISSUE_QUEUE_NAME = "BATCH_ISSUE_QUEUE_NAME";

    @Autowired
    private IETEInvoiceMapper ieteInvoiceMapper;
    @Autowired
    private IETCommonInnerService ietCommonInnerService;
    @Autowired
    private IETOrderService ietOrderService;
    @Autowired
    private IETChargebillService ietChargebillService;
    @Autowired
    private IGenerateVoucherService iGenerateVoucherService;
    @Autowired
    private IGenerateOriSignService iGenerateOriSignService;
    @Autowired
    private IETVoucherService ietVoucherService;
    @Autowired
    private IEGenerateFeignClient ieGenerateFeignClient;
    @Autowired
    private TaskExecutor taskExecutor;
    @Autowired
    private IETOrderChargebillInnerService ietOrderChargebillInnerService;
    @Autowired
    private ISendMessageService iSendMessageService;
    @Autowired
    private IEInvoiceSignatureService ieInvoiceSignatureService;
    @Autowired
    private IEGenerateDetailMapper ieGenerateDetailMapper;
    @Autowired
    private CreateFile createFile;
    @Autowired
    private IETCreateVoucherFileService ietCreateVoucherFileService;
    @Autowired
    private ICommonService iCommonService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IETOrderMapper iETOrderMapper;
    @Autowired
    private IETOrderDetailMapper iETOrderDetailMapper;
    @Autowired
    private IETOrderDescMapper ietOrderDescMapper;
    @Autowired
    private IETChargebillMapper iETChargeBillMapper;
    @Autowired
    private IEinvoiceDetailMapper iEinvoiceDetailMapper;
    @Autowired
    private IOrderInnerService iOrderInnerService;
    @Autowired
    private IChargeBillService iChargeBillService;
    @Autowired
    private IBillAcitveMapper iBillAcitveMapper;
    @Autowired
    private IETVoucherMapper ietVoucherMapper;
    @Autowired
	private IETChargebillMapper ietChargebillMapper;
    @Autowired
    private IETQueueService ietQueueService;
    @Autowired
	private IEticketNumMapper iEticketNumMapper;

    //private String content = "【河北财政非税收入】缴款码：orderno，执收单位：agencyname，交款人：payer，金额：amt元，项目：item。日期：date";
    @Value("${FASP.ISSENDPHONE}")
    private String isSendPhone;
    @Value("${SupervisorPartySeal.SealHash}")
    private String supervisorPartySealHash;
    @Value("${SupervisorPartySeal.SealId}")
    private String supervisorPartySealId;
    @Value("${SupervisorPartySeal.SealName}")
    private String supervisorPartySealName;

    @Override
    @Transactional
    public OrderChargeBillViewDTO save(OrderChargebillDTO orderChargebillDTO, EInvoiceDTO eInvoiceDTO) {
        logger.info(LOGGER_HEADER + " 进入电子票据保存接口");
        // 1. 判断参数是否合法
        String content = "【河北财政非税收入】缴款码：orderno，执收单位：agencyname，交款人：payer，金额：amt元，项目：item。日期：date（请在缴款完成后索取）";
        OrderDTO orderDTO = orderChargebillDTO.getOrderDTO();
        ChargeBillDTO chargeBillDTO = orderChargebillDTO.getChargeBillDTO();
        OrderDescDTO orderDescDTO = orderDTO.getOrderDescDTO();
        List<OrderDetailDTO> orderDetailDTOList = orderDTO.getOrderDetailDTOList();
        List<EInvoiceDetailDTO> detailDTOList = eInvoiceDTO.getDetailDTOList();
        int userID = orderChargebillDTO.getBaseUserID();
        String errorMsg = checkParams(orderDTO, chargeBillDTO, orderDescDTO, orderDetailDTOList, eInvoiceDTO, detailDTOList, userID);
        if(!errorMsg.equals("")){
            throw new EInvoiceException(errorMsg);
        }
        // 2.验签
        String busiOrderNO = orderDTO.getBusiOrderNO();
        String signOriXML = iGenerateOriSignService.generateBusitype17SignXML(orderChargebillDTO);
        String signData = orderChargebillDTO.getSignData();
        Map<String, String> result = ietVoucherService.verifyCert("", signOriXML, signData);
        String code = result.get("code") == null ? "0" : result.get("code");
        logger.info(LOGGER_HEADER + " 订单号：" + busiOrderNO + "的验签结果为：" + code);
        if(!code.equals("1")){
            throw new EInvoiceException("验签失败" + result.get("msg"));
        }
        String serverDateTime = iCommonService.getServerDateTime();
        String writeDate = serverDateTime.substring(0,10);
        String overDueDate = iCommonService.getWorkDate(10, writeDate);

        // 3.保存订单
        orderDTO.setOverdueDate(overDueDate);
        ietOrderService.save(orderDTO);
        chargeBillDTO.setOrderBillSN(orderDTO.getBillSN());
        String issueDate = eInvoiceDTO.getIssueDate();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(issueDate.substring(0,4)).append("-").append(issueDate.substring(4,6)).append("-").append(issueDate.substring(6));
        chargeBillDTO.setFillDate(stringBuilder.toString());
        ietChargebillService.save(chargeBillDTO);
        // 4.保存凭证
        ETVoucherDTO etVoucherDTO = iGenerateVoucherService.generateETVoucherDTO(orderDTO.getBillSN(),orderChargebillDTO.getSignData(),null,chargeBillDTO.getBillNum(),-1);
        if(etVoucherDTO == null){
            logger.info(LOGGER_HEADER + "保存凭证失败null");
            throw new EInvoiceException("保存失败");
        }
        etVoucherDTO.setIsVerify(1);
        etVoucherDTO.setSignData(signData);
        etVoucherDTO.setSignOriginData(signOriXML);
        etVoucherDTO.setWriteDate(serverDateTime);
        int ret = ietVoucherService.saveETVoucherDTO(etVoucherDTO);
        if(ret <= 0){
            logger.info(LOGGER_HEADER + "保存凭证数据失败");
            throw new EInvoiceException("保存凭证数据失败");
        }
        // 5.保存电子票据
        eInvoiceDTO.setEmail(orderDescDTO.geteMail());
        ret = generateEInvoice(eInvoiceDTO);
        if(ret <=0 ){
            logger.info(LOGGER_HEADER + "电子票据保存失败");
            throw new EInvoiceException("电子票据保存失败");
        }

        String linkTel = orderDescDTO.getLinkTel();
        String billSN = orderDTO.getBillSN();
        OrderChargeBillViewDTO orderChargebillViewBySN = ietOrderChargebillInnerService.getOrderChargebillViewBySN(billSN);
        if(orderChargebillViewBySN == null){
            logger.info(LOGGER_HEADER + "保存失败:" + billSN);
            throw new EInvoiceException("保存失败");
        }
        //如果手机号不为空并且是开票时（未收款时），发送短信
        if(isSendPhone != null && isSendPhone.equals("1") && orderChargebillViewBySN.getIsBankAffirm() == 0 && !StringUtil.isNull(linkTel)){
            String recID = String.valueOf(orderChargebillViewBySN.getWriteUser());
            orderChargebillViewBySN.getBusiOrderNO();
            content = content.replace("orderno", busiOrderNO);//StringFormatter.format(content,busiOrderNO).getValue();
            content = content.replace("payer",orderChargebillViewBySN.getPayer());
            DecimalFormat fnum = new DecimalFormat("0.00");
            content = content.replace("amt", fnum.format(orderChargebillViewBySN.getAmt()));
            List<AgencyDTO> agencyInfoByID = ieteInvoiceMapper.getAgencyInfoByID(orderDTO.getAgency());
            if(agencyInfoByID != null && agencyInfoByID.size() >0) {
                AgencyDTO agencyDTO = agencyInfoByID.get(0);
                String agencyName = agencyDTO.getItemName();
                content = content.replace("agencyname", agencyName);
            }else{
                content = content.replace("agencyname", "");
            }
            StringBuilder item = new StringBuilder();
            for (OrderDetailDTO orderDetailDTO : orderDetailDTOList) {
                List<IncItemDTO> incItemDTOS = ieteInvoiceMapper.getIncItemByID(orderDetailDTO.getIncItem());
                if(incItemDTOS != null && incItemDTOS.size() > 0) {
                    IncItemDTO incItemDTO = incItemDTOS.get(0);
                    String itemName = incItemDTO.getItemName();
                    item.append(itemName).append("、");
                }
            }
            String items = item.toString();
            if(!StringUtil.isNull(items))
                items = items.substring(0,items.length()-1);
            content = content.replace("item",items);
            content = content.replace("date",orderChargebillViewBySN.getWriteDate().substring(0,10));
            String today = ieteInvoiceMapper.getNow().substring(0,10);
            //开启线程发送短信
            sendPhoneMsg(recID,content,linkTel,today);
        }
        //生成png文件
        createPNG(eInvoiceDTO);
        //生成pdf文件
        //createPDF(eInvoiceDTO);
        //制作开票凭证
        if (orderDTO.getIsWaitAffirm()==1 || orderDTO.getIsAdjust()==1){
            CreateVoucherFileVSbankSeal(etVoucherDTO);
        }else {
            createVoucher(etVoucherDTO);
        }
        return orderChargebillViewBySN;
    }

    @Override
    @Transactional
    public List<OrderChargeBillViewDTO> batchSave(List<OrderChargebillDTO> orderChargebillDTOList, List<EInvoiceDTO> eInvoiceDTOList) {
        //String content = "【河北财政非税收入】缴款码：orderno，执收单位：agencyname，交款人：payer，金额：amt元，项目：item。日期：date（请在缴款完成后索取）";
        if(orderChargebillDTOList == null || orderChargebillDTOList.size() <= 0
                || eInvoiceDTOList == null || eInvoiceDTOList.size() <= 0
                || orderChargebillDTOList.size() != eInvoiceDTOList.size()){
            throw new EInvoiceException("参数格式错误");
        }

        int size = orderChargebillDTOList.size();
        logger.info("集合大小" + size);
        // 1. 验证订单数据
        List<OrderDTO> orderDTOS = new ArrayList<>();
        String serverDateTime = iCommonService.getServerDateTime();
        String writeDate = serverDateTime.substring(0,10);
        String overDueDate = iCommonService.getWorkDate(10, writeDate);
        List<ChargeBillDTO> chargeBillDTOS = new ArrayList<>();
        List<OrderDescDTO> orderDescDTOS = new ArrayList<>();
        List<OrderDetailDTO> orderDetailDTOS = new ArrayList<>();
        List<EInvoiceDetailDTO> eInvoiceDetailDTOS = new ArrayList<>();
        List<ETVoucherDTO> voucherDTOS = new ArrayList<>();
        int admDivID = 0;
        int agencyID = 0;
        int billTypeID = 0;
        int userID = 0;
        String checkUser = "";
        List<Map<String,String>> phoneList = new ArrayList<>();
        List<String> billNumS = new ArrayList<>();
        List<String> billSNList = new ArrayList<>();
        List<OrderChargeBillViewDTO> orderChargeBillViewDTOS = new ArrayList<>();
        for (int i = 0;i<size;i++) {
            String content = "【河北财政非税收入】缴款码：orderno，执收单位：agencyname，交款人：payer，金额：amt元，项目：item。日期：date（请在缴款完成后索取）";
            Map<String,String> map = new HashMap<>();
            OrderChargebillDTO orderChargebillDTO = orderChargebillDTOList.get(i);
            EInvoiceDTO eInvoiceDTO = eInvoiceDTOList.get(i);
            OrderDTO orderDTO = orderChargebillDTO.getOrderDTO();
            ChargeBillDTO chargeBillDTO = orderChargebillDTO.getChargeBillDTO();
            OrderDescDTO orderDescDTO = orderDTO.getOrderDescDTO();
            List<OrderDetailDTO> orderDetailDTOList = orderDTO.getOrderDetailDTOList();
            List<EInvoiceDetailDTO> detailDTOList = eInvoiceDTO.getDetailDTOList();
            admDivID = orderDTO.getAdmDiv();
            agencyID = orderDTO.getAgency();
            billTypeID = chargeBillDTO.getBillType();
            userID = orderChargebillDTO.getBaseUserID();
            billNumS.add(eInvoiceDTO.geteInvoiceNumber());
            checkUser = eInvoiceDTO.getChecker();
            String errorMsg = checkParams(orderDTO, chargeBillDTO, orderDescDTO, orderDetailDTOList, eInvoiceDTO, detailDTOList, userID);
            if(!errorMsg.equals("")){
                throw new EInvoiceException(errorMsg);
            }
            // 2.验签
            String busiOrderNO = orderDTO.getBusiOrderNO();
            String signOriXML = iGenerateOriSignService.generateBusitype17SignXML(orderChargebillDTO);
            String signData = orderChargebillDTO.getSignData();
            Map<String, String> result = ietVoucherService.verifyCert("", signOriXML, signData);
            String code = result.get("code") == null ? "0" : result.get("code");
            logger.info(LOGGER_HEADER + " 订单号：" + busiOrderNO + "的验签结果为：" + code);
            if(!code.equals("1")){
                throw new EInvoiceException("验签失败" + result.get("msg"));
            }
            // 3. 电子票据验证信息
            eInvoiceDTO.setSuperVisorSealHash(supervisorPartySealHash);
            eInvoiceDTO.setSuperVisorSealId(supervisorPartySealId);
            eInvoiceDTO.setSuperVisorSealName(supervisorPartySealName);
            eInvoiceDTO.setImgStatus(0);
            eInvoiceDTO.setEmail(orderDescDTO.geteMail());
            ReturnData returnSign = ieInvoiceSignatureService.checkAgencySign(eInvoiceDTO);
            Map<String, String> signMap =null;
            if (returnSign.getCode() >= 1) {
                signMap = (Map<String, String>) returnSign.getSingleData();
                //验证用户是否授权公章
                String cn = signMap.get("cn");
                String sn = signMap.get("sn");
                int authc = ieteInvoiceMapper.getAuthcByCNSN(cn, sn.toUpperCase());
                if (StringUtil.isNull(cn) || StringUtil.isNull(sn) || authc != 1) {
                    logger.error("用户未绑定印章");
                    throw new EInvoiceException("用户未绑定印章！");
                }
            } else {
                logger.error("验证签名失败");
                throw new EInvoiceException("验证签名失败！");
            }
            ReturnData authData = checkGenerateAuth(eInvoiceDTO);
            if (0==authData.getCode()) {
                logger.error("开票权限验证失败");
                throw new EInvoiceException("开票权限验证失败！");
            }
            ReturnData signData1 = makeEinvoice(eInvoiceDTO, signMap.get("xmlSign"));
            if (0>=signData1.getCode()) {
                logger.error("获取签名xml失败:"+signData1.getMsg());
                throw new EInvoiceException("获取签名xml失败！");
                //进行回滚
            }
            String eInvoiceXML = (String) signData1.getSingleData();
            eInvoiceDTO.seteInvoiceXml(eInvoiceXML);
            String orderBillSN = UuidUtils.getUuid();
            orderDTO.setBillSN(orderBillSN);
            orderDTO.setOverdueDate(overDueDate);
            chargeBillDTO.setOrderBillSN(orderBillSN);
            chargeBillDTO.setBusiOrderNO(busiOrderNO);
            String issueDate = eInvoiceDTO.getIssueDate();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(issueDate.substring(0,4)).append("-").append(issueDate.substring(4,6)).append("-").append(issueDate.substring(6));
            chargeBillDTO.setFillDate(stringBuilder.toString());
            for (OrderDetailDTO orderDetailDTO : orderDetailDTOList) {
                orderDetailDTO.setOrderBillSN(orderBillSN);
                orderDetailDTO.setBusiOrderNO(busiOrderNO);
            }
            orderDescDTO.setBusiOrderNO(busiOrderNO);
            orderDescDTO.setOrderBillSN(orderBillSN);
            orderDTOS.add(orderDTO);
            orderDetailDTOS.addAll(orderDetailDTOList);
            orderDescDTOS.add(orderDescDTO);
            chargeBillDTOS.add(chargeBillDTO);
            eInvoiceDetailDTOS.addAll(eInvoiceDTO.getDetailDTOList());

            if(!StringUtil.isNull(orderDescDTO.getLinkTel())){
                map.put("phone",orderDescDTO.getLinkTel());
                content = content.replace("orderno", busiOrderNO);//StringFormatter.format(content,busiOrderNO).getValue();
                content = content.replace("payer",orderDTO.getPayer());
                List<AgencyDTO> agencyInfoByID = ieteInvoiceMapper.getAgencyInfoByID(orderDTO.getAgency());
                if(agencyInfoByID != null && agencyInfoByID.size() > 0){
                    AgencyDTO agencyDTO = agencyInfoByID.get(0);
                    String itemName = agencyDTO.getItemName();
                    content.replace("agencyname",itemName);
                }else{
                    content.replace("agencyname","");
                }
                StringBuilder item = new StringBuilder();
                for (OrderDetailDTO orderDetailDTO : orderDetailDTOList) {
                    List<IncItemDTO> incItemByID = ieteInvoiceMapper.getIncItemByID(orderDetailDTO.getIncItem());
                    if(incItemByID != null && incItemByID.size() > 0){
                        IncItemDTO incItemDTO = incItemByID.get(0);
                        item.append(incItemDTO.getItemName()).append("、");
                    }
                }
                String items = item.toString();
                if(!StringUtil.isNull(items))
                    items = items.substring(0,items.length() - 1);
                content.replace("item",items);
                DecimalFormat fnum = new DecimalFormat("0.00");
                content = content.replace("amt", fnum.format(orderDTO.getAmt()));
                content = content.replace("date",serverDateTime.substring(0,10));
                String today = ieteInvoiceMapper.getNow().substring(0,10);
                map.put("today",today);
                map.put("userID",String.valueOf(userID));
                map.put("content",content);
                phoneList.add(map);
            }
            billSNList.add(orderBillSN);
            List<AgencyDTO> agencyDTOList = ieteInvoiceMapper.getAgencyInfoByID(agencyID);
            if(agencyDTOList != null && agencyDTOList.size() > 0){
                AgencyDTO agencyDTO = agencyDTOList.get(0);
                OrderChargeBillViewDTO orderChargeBillViewDTO = new OrderChargeBillViewDTO();
                orderChargeBillViewDTO.setBusiOrderNO(busiOrderNO);
                orderChargeBillViewDTO.setV1(orderDescDTO.getV1());
                orderChargeBillViewDTO.setAgencyCode(agencyDTO.getItemCode());
                orderChargeBillViewDTO.setAgencyName(agencyDTO.getItemName());
                orderChargeBillViewDTO.setPayer(orderDTO.getPayer());
                orderChargeBillViewDTO.setBillNum(chargeBillDTO.getBillNum());
                orderChargeBillViewDTO.setVertifyCode(chargeBillDTO.getVertifyCode());
                orderChargeBillViewDTO.setAmt(orderDTO.getAmt());
                orderChargeBillViewDTO.setWriteDate(writeDate);
                orderChargeBillViewDTOS.add(orderChargeBillViewDTO);
            }

        }
        // 批量更新票据描述表
        String year = serverDateTime.substring(0,4);
        //保存数据
        logger.info("---------> 进入分包批量插入数据接口，总条数：" + size);
        int count = size % 200 == 0 ? size / 200 : (size / 200 + 1);
        logger.info("---------> 进入分包批量插入数据接口，总次数：" + count);
        for (int i = 0; i < count; i++) {
            List<String> tempList = new ArrayList<>();
            List<OrderDTO> tempOrderDTOS = new ArrayList<>();
            List<OrderDescDTO> tempOrderDescDTOS = new ArrayList<>();
            List<ChargeBillDTO> tempChargeBillDTOS = new ArrayList<>();
            List<EInvoiceDTO> tempEinvoiceDTOS = new ArrayList<>();
            if(count == 1){
                tempList=billNumS.subList(0,size);
                tempOrderDTOS = orderDTOS.subList(0,size);
                tempOrderDescDTOS = orderDescDTOS.subList(0,size);
                tempChargeBillDTOS = chargeBillDTOS.subList(0,size);
                tempEinvoiceDTOS = eInvoiceDTOList.subList(0,size);
            }else{
                if(i != (count -1)) {
                    tempList=billNumS.subList(i * 200, (i + 1) * 200);
                    tempOrderDTOS=orderDTOS.subList(i * 200, (i + 1) * 200);
                    tempOrderDescDTOS=orderDescDTOS.subList(i * 200, (i + 1) * 200);
                    tempChargeBillDTOS=chargeBillDTOS.subList(i * 200, (i + 1) * 200);
                    tempEinvoiceDTOS=eInvoiceDTOList.subList(i * 200, (i + 1) * 200);
                }else{
                    tempList=billNumS.subList(i*200,size);
                    tempOrderDTOS=orderDTOS.subList(i*200,size);
                    tempOrderDescDTOS=orderDescDTOS.subList(i*200,size);
                    tempChargeBillDTOS=chargeBillDTOS.subList(i*200,size);
                    tempEinvoiceDTOS=eInvoiceDTOList.subList(i*200,size);
                }

            }
            logger.info("---------> 进入分包批量插入数据接口，即将执行第：" + i + "次保存操作");
            try {
                ieteInvoiceMapper.batchUpdateBillActive(admDivID,agencyID,billTypeID,tempList,checkUser,year);
                iETOrderMapper.batchInsertSelectiveByListEntity(tempOrderDTOS);
                ietOrderDescMapper.batchInsertSelectiveByListEntity(tempOrderDescDTOS);
                iETChargeBillMapper.batchSaveChargebill(tempChargeBillDTOS);
//                for (ChargeBillDTO tempChargeBillDTO : tempChargeBillDTOS) {
//                    iETChargeBillMapper.insertSelectiveByEntity(tempChargeBillDTO);
//                }
                ieteInvoiceMapper.batchSaveEinvoice(tempEinvoiceDTOS);
//                for (EInvoiceDTO tempEinvoiceDTO : tempEinvoiceDTOS) {
//                    ieteInvoiceMapper.insertSelectiveByEntity(tempEinvoiceDTO);
//                }
            }catch (Exception e){
                logger.info("批量保存票据信息失败" + e);
                throw new EInvoiceException("批量保存票据信息失败");
            }
        }
        int size1 = orderDetailDTOS.size();
        count = size1 % 200 == 0 ? size1 / 200 : (size1 / 200 + 1);
        logger.info("---------> 进入细单分包批量插入数据接口，总次数：" + count);
        for (int i = 0; i < count; i++) {
            List<EInvoiceDetailDTO> tempEinvoiceDetailDTOS = new ArrayList<>();
            List<OrderDetailDTO> tempOrderDetailDTOS = new ArrayList<>();
            if(count == 1){
                tempEinvoiceDetailDTOS=eInvoiceDetailDTOS.subList(0,size1);
                tempOrderDetailDTOS = orderDetailDTOS.subList(0,size1);
            }else{
                if(i != (count -1)) {
                    tempEinvoiceDetailDTOS=eInvoiceDetailDTOS.subList(i * 200, (i + 1) * 200);
                    tempOrderDetailDTOS=orderDetailDTOS.subList(i * 200, (i + 1) * 200);
                }else{
                    tempEinvoiceDetailDTOS=eInvoiceDetailDTOS.subList(i*200,size1);
                    tempOrderDetailDTOS=orderDetailDTOS.subList(i*200,size1);
                }

            }
            logger.info("---------> 进入分包批量插入数据接口，即将执行第：" + i + "次保存操作");
            try {
                iETOrderDetailMapper.batchInsertSelectiveByListEntity(tempOrderDetailDTOS);
                iEinvoiceDetailMapper.batchInsertSelectiveByListEntity(tempEinvoiceDetailDTOS);
            }catch (Exception e){
                logger.info("批量保存票据细单信息失败" + e);
                throw new EInvoiceException("批量保存票据细单信息失败");
            }
        }

        //批量生成凭证
        for (int i = 0;i<size;i++) {
            OrderChargebillDTO orderChargebillDTO = orderChargebillDTOList.get(i);
            OrderDTO orderDTO = orderChargebillDTO.getOrderDTO();
            ChargeBillDTO chargeBillDTO = orderChargebillDTO.getChargeBillDTO();
            ETVoucherDTO etVoucherDTO = iGenerateVoucherService.generateETVoucherDTO(orderDTO.getBillSN(), orderChargebillDTO.getSignData(), null, chargeBillDTO.getBillNum(), -1);
            if (etVoucherDTO == null) {
                logger.info(LOGGER_HEADER + "保存凭证失败null");
                throw new EInvoiceException("保存失败");
            }
            String signOriXML = iGenerateOriSignService.generateBusitype17SignXML(orderChargebillDTO);
            String signData = orderChargebillDTO.getSignData();
            etVoucherDTO.setIsVerify(1);
            etVoucherDTO.setSignData(signData);
            etVoucherDTO.setSignOriginData(signOriXML);
            etVoucherDTO.setWriteDate(serverDateTime);
            voucherDTOS.add(etVoucherDTO);
        }
        int size2 = voucherDTOS.size();
        count = size2 % 200 == 0 ? size2 / 200 : (size2 / 200 + 1);
        logger.info("---------> 进入细单分包批量插入数据接口，总次数：" + count);
        for (int i = 0; i < count; i++) {
            List<ETVoucherDTO> tempEinvoiceDetailDTOS = new ArrayList<>();
            if(count == 1){
                tempEinvoiceDetailDTOS= voucherDTOS.subList(0,size2);
            }else{
                if(i != (count -1)) {
                    tempEinvoiceDetailDTOS=voucherDTOS.subList(i * 200, (i + 1) * 200);
                }else{
                    tempEinvoiceDetailDTOS=voucherDTOS.subList(i*200,size2);
                }

            }
            logger.info("---------> 进入分包批量插入数据接口，即将执行第：" + i + "次保存操作");
            try {
                ietVoucherMapper.batchInsertSelectiveByListEntity(tempEinvoiceDetailDTOS);
            }catch (Exception e){
                logger.info("批量保存凭证失败：" + e);
                throw new EInvoiceException("批量保存凭证失败");
            }
        }
        for (ETVoucherDTO etVoucherDTO : voucherDTOS) {
            createVoucher(etVoucherDTO);
        }
        for (EInvoiceDTO eInvoiceDTO : eInvoiceDTOList) {
            createPNG(eInvoiceDTO);
        }
        /*for (EInvoiceDTO eInvoiceDTO : eInvoiceDTOList) {
            createPDF(eInvoiceDTO);
        }*/
        //批量发送短信
        for (Map<String, String> stringStringMap : phoneList) {
            String recID = stringStringMap.get("userID");
            String content = stringStringMap.get("content");
            String linkTel = stringStringMap.get("phone");
            String today = stringStringMap.get("today");
            sendPhoneMsg(recID,content,linkTel,today);
        }
        return orderChargeBillViewDTOS;
    }

    @Override
    @Transactional
    public EIBillNumDTO getEIBillNum(int admDivID, int agencyID, int billTypeID, int year,String payerPartyName,double totalAmout) {
        EIBillNumDTO eiBillNumDTO = new EIBillNumDTO();
        // 1.获取票号
        HashMap<String, Object> numberMap = new HashMap<>();
        numberMap.put("billtypeid",billTypeID);
        numberMap.put("agencyid",agencyID);
        numberMap.put("admdivid",admDivID);
        numberMap.put("year",year);
        ReturnData returnNumber = ieGenerateFeignClient.getInvoiceNumber(numberMap);
        if (returnNumber.getCode()>=1) {
            String number = String.valueOf(returnNumber.getSingleData());
            eiBillNumDTO.seteInvoiceNumber(number);
        }else{
            logger.error(LOGGER_HEADER + "获取电子票据号码失败");
            throw new EInvoiceException("获取电子票据号码失败！");
        }
        //
        //查询挂接票据模板信息
        List<Map<String, Object>> templateList = ieteInvoiceMapper.getBillTypeByID(billTypeID);
        if(templateList == null || templateList.size() != 1){
            logger.info(LOGGER_HEADER + " 电子票据模板不存在：" + billTypeID);
            throw new EInvoiceException("电子票据模板不存在！");
        }
        //获取模板信息
        Map<String, Object> map = templateList.get(0);
        String itemcode = map.get("ITEMCODE").toString();
        String itemname = map.get("ITEMNAME").toString();
        String code = "13" + itemcode + String.valueOf(year).substring(2);
        eiBillNumDTO.seteInvoiceCode(code);
        // 3.反转获取ID
        eiBillNumDTO.seteInvoiceId(new StringBuffer((eiBillNumDTO.geteInvoiceCode()+ "-" + eiBillNumDTO.geteInvoiceNumber())).reverse().toString());
        //开票时间,开票日期
        String dateTime = ieteInvoiceMapper.getNow();
        eiBillNumDTO.setIssueDate(dateTime.substring(0,10).replace("-",""));
        eiBillNumDTO.setIssueTime(dateTime.substring(11));
        // 2.计算随机数
        List<String> recNameList = ieteInvoiceMapper.getRecNameByAdmdivId(admDivID);
        if (null!=recNameList&&recNameList.size()>=1) {
            String ReceName = recNameList.get(0);
            eiBillNumDTO.setRandomNumber(EInoviceUtils.initCheckCode("" + itemname +
                    code + eiBillNumDTO.geteInvoiceNumber() +
                    eiBillNumDTO.getIssueDate() + ReceName +
                    payerPartyName + totalAmout));

        }else{
            logger.error(LOGGER_HEADER + "当前区划下未挂接收款银行信息:" + admDivID);
            throw new EInvoiceException("当前区划下未挂接收款银行信息！");
        }
        // 4.设置DTO属性值
        return eiBillNumDTO;
    }

    @Override
    public EInvoiceDTO getEInvoicePartInfo(int admDivID, int agencyID, int userID, int billTypeID, int year,String payerPartyName,double totalAmout){
        logger.info(LOGGER_HEADER + " 开始获取电子票据相关信息");
        String dateTime = ieteInvoiceMapper.getNow();
        //datetime --> 2019-07-24 11:28:41
        String yearStr = String.valueOf(year);
        if(year <= 0){
            yearStr = dateTime.substring(0,4);
            year = Integer.parseInt(yearStr);
        }
        List<UserDTO> userDTOS = ieteInvoiceMapper.getUserInfoByUserID(userID);
        if(userDTOS == null || userDTOS.size() != 1){
            logger.info(LOGGER_HEADER + " 用户不存在：" + userID);
            throw new EInvoiceException("用户不存在！");
        }
        UserDTO userDTO = userDTOS.get(0);
        String userSN = userDTO.getUser_SN();
        String userCN = userDTO.getUser_CN();
        List<Map<String,Object>> templateInfos = ieteInvoiceMapper.getEITemplateInfo(admDivID,agencyID,userCN,userSN.toUpperCase());
        if(templateInfos == null || templateInfos.size() != 1){
            logger.info(LOGGER_HEADER + " 用户未绑定银章或绑定信息有误：" + userID);
            throw new EInvoiceException("用户未绑定银章或绑定信息有误！");
        }
        int count = ieteInvoiceMapper.getAgencyVsBill(agencyID,billTypeID);
        if(count <= 0){
            logger.info(LOGGER_HEADER + " 单位未挂接电子票据：" + billTypeID);
            throw new EInvoiceException("单位未挂接电子票据！");
        }
        // templateInfo --> ITEMID  ITEMNAME  SEALHEX
        Map<String, Object> templateInfo = templateInfos.get(0);
        List<Map<String, Object>> billTypeInfoS = ieteInvoiceMapper.getBillTypeByID(billTypeID);
        if(billTypeInfoS == null || billTypeInfoS.size() != 1){
            logger.info(LOGGER_HEADER + " 电子票据模板不存在：" + billTypeID);
            throw new EInvoiceException("电子票据模板不存在！");
        }

        Map<String, Object> billTypeInfo = billTypeInfoS.get(0);
        String busiOrderNO = "";
        try {
            busiOrderNO = ietCommonInnerService.getBusiOrderNO(admDivID, yearStr);
        }catch (Exception e){
            logger.info(LOGGER_HEADER + " 获取电子缴款码失败：" + admDivID);
            throw new EInvoiceException("获取电子缴款码失败！");
        }
        EIBillNumDTO eiBillNumDTO = getEIBillNum(admDivID, agencyID, billTypeID, year,payerPartyName,totalAmout);
        if(eiBillNumDTO == null){
            logger.info(LOGGER_HEADER + " 获取电子票据号码为空");
            throw new EInvoiceException("获取电子票据号码失败！");
        }
        EInvoiceDTO eInvoiceDTO = new EInvoiceDTO();
        eInvoiceDTO.seteInvoiceName(billTypeInfo.get("ITEMNAME").toString());
        eInvoiceDTO.seteInvoiceCode(eiBillNumDTO.geteInvoiceCode());
        eInvoiceDTO.seteInvoiceNumber(eiBillNumDTO.geteInvoiceNumber());
        eInvoiceDTO.setRandomNumber(eiBillNumDTO.getRandomNumber());
        eInvoiceDTO.seteInvoiceId(eiBillNumDTO.geteInvoiceId());
        eInvoiceDTO.seteInvoiceSpecimenCode(billTypeInfo.get("EINVOICESPECIMENCODE").toString());
        eInvoiceDTO.setSuperVisorAreaCode("130000");
        eInvoiceDTO.setIssueDate(eiBillNumDTO.getIssueDate());
        eInvoiceDTO.setIssueTime(eiBillNumDTO.getIssueTime());
        eInvoiceDTO.setPayCode(busiOrderNO);
        eInvoiceDTO.setAgencySealId(templateInfo.get("ITEMID").toString());
        eInvoiceDTO.setAgencySealName(templateInfo.get("ITEMNAME").toString());
        eInvoiceDTO.setAgencySealHash(templateInfo.get("SEALHEX").toString());
        return eInvoiceDTO;
    }

    @Override
    public List<EInvoiceDTO> getEInvoicePartInfos(EIParamForGetPartInfoDTO eiParamForGetPartInfoDTO) {
        if(eiParamForGetPartInfoDTO == null){
            throw new EInvoiceException("参数格式错误");
        }
        int admDivID = eiParamForGetPartInfoDTO.getAdmDivID();
        int agencyID = eiParamForGetPartInfoDTO.getAgencyID();
        int billTypeID = eiParamForGetPartInfoDTO.getBillTypeID();
        int year = eiParamForGetPartInfoDTO.getYear();
        List<String> payerPartyNameList = eiParamForGetPartInfoDTO.getPayerPartyNameList();
        List<Double> totalAmoutList = eiParamForGetPartInfoDTO.getTotalAmoutList();
        int payerCount = payerPartyNameList == null ? 0 : payerPartyNameList.size();
        int amtCount = totalAmoutList == null ? 0 : totalAmoutList.size();
        if(payerCount == 0 || amtCount == 0 || payerCount != amtCount){
            throw new EInvoiceException("参数格式错误");
        }
        int type = eiParamForGetPartInfoDTO.getType();
        int userID = eiParamForGetPartInfoDTO.getBaseUserID();
        String dateTime = ieteInvoiceMapper.getNow();
        //datetime --> 2019-07-24 11:28:41
        String yearStr = String.valueOf(year);
        if(year <= 0){
            yearStr = dateTime.substring(0,4);
            year = Integer.parseInt(yearStr);
        }
        List<UserDTO> userDTOS = ieteInvoiceMapper.getUserInfoByUserID(userID);
        if(userDTOS == null || userDTOS.size() != 1){
            logger.info(LOGGER_HEADER + " 用户不存在：" + userID);
            throw new EInvoiceException("用户不存在！");
        }
        UserDTO userDTO = userDTOS.get(0);
        String userSN = userDTO.getUser_SN();
        String userCN = userDTO.getUser_CN();
        List<Map<String,Object>> templateInfos = ieteInvoiceMapper.getEITemplateInfo(admDivID,agencyID,userCN,userSN.toUpperCase());
        if(templateInfos == null || templateInfos.size() != 1){
            logger.info(LOGGER_HEADER + " 用户未绑定银章或绑定信息有误：" + userID);
            throw new EInvoiceException("用户未绑定银章或绑定信息有误！");
        }
        // templateInfo --> ITEMID  ITEMNAME  SEALHEX
        Map<String, Object> templateInfo = templateInfos.get(0);
        List<Map<String, Object>> billTypeInfoS = ieteInvoiceMapper.getBillTypeByID(billTypeID);
        if(billTypeInfoS == null || billTypeInfoS.size() != 1){
            logger.info(LOGGER_HEADER + " 电子票据模板不存在：" + billTypeID);
            throw new EInvoiceException("电子票据模板不存在！");
        }
        Map<String, Object> billTypeInfo = billTypeInfoS.get(0);
        List<EInvoiceDTO> busiOrderNOList = new ArrayList<>();
        for(int i = 0;i <payerCount;i++) {
            String busiOrderNO = "";
            if (type == 0 || type == 2){
                try {
                    busiOrderNO = ietCommonInnerService.getBusiOrderNO(admDivID, yearStr);
                } catch (Exception e) {
                    logger.info(LOGGER_HEADER + " 获取电子缴款码失败：" + admDivID);
                    throw new EInvoiceException("获取电子缴款码失败！");
                }
            }
            EIBillNumDTO eiBillNumDTO = getEIBillNum(admDivID, agencyID, billTypeID, year,payerPartyNameList.get(i),totalAmoutList.get(i));
            if(eiBillNumDTO == null){
                logger.info(LOGGER_HEADER + " 获取电子票据号码为空");
                throw new EInvoiceException("获取电子票据号码失败！");
            }
            EInvoiceDTO eInvoiceDTO = new EInvoiceDTO();
            eInvoiceDTO.seteInvoiceName(billTypeInfo.get("ITEMNAME").toString());
            eInvoiceDTO.seteInvoiceCode(eiBillNumDTO.geteInvoiceCode());
            eInvoiceDTO.seteInvoiceNumber(eiBillNumDTO.geteInvoiceNumber());
            eInvoiceDTO.setRandomNumber(eiBillNumDTO.getRandomNumber());
            eInvoiceDTO.seteInvoiceId(eiBillNumDTO.geteInvoiceId());
            eInvoiceDTO.seteInvoiceSpecimenCode(billTypeInfo.get("EINVOICESPECIMENCODE").toString());
            eInvoiceDTO.setSuperVisorAreaCode("130000");
            eInvoiceDTO.setIssueDate(eiBillNumDTO.getIssueDate());
            eInvoiceDTO.setIssueTime(eiBillNumDTO.getIssueTime());
            eInvoiceDTO.setPayCode(busiOrderNO);
            eInvoiceDTO.setAgencySealId(templateInfo.get("ITEMID").toString());
            eInvoiceDTO.setAgencySealName(templateInfo.get("ITEMNAME").toString());
            eInvoiceDTO.setAgencySealHash(templateInfo.get("SEALHEX").toString());
            busiOrderNOList.add(eInvoiceDTO);
        }
        return busiOrderNOList;
    }

    @Override
	@Transactional
	public int generateEInvoice(EInvoiceDTO eInvoiceDTO) {
		String year = ieteInvoiceMapper.getYear();
		eInvoiceDTO.setSuperVisorSealHash(supervisorPartySealHash);
		eInvoiceDTO.setSuperVisorSealId(supervisorPartySealId);
		eInvoiceDTO.setSuperVisorSealName(supervisorPartySealName);
		eInvoiceDTO.setImgStatus(0);
		ReturnData returnSign = ieInvoiceSignatureService.checkAgencySign(eInvoiceDTO);
		Map<String, String> signMap =null;
		if (returnSign.getCode() >= 1) {
			signMap = (Map<String, String>) returnSign.getSingleData();
			//验证用户是否授权公章
			String cn = signMap.get("cn");
			String sn = signMap.get("sn");
			int authc = ieteInvoiceMapper.getAuthcByCNSN(cn, sn.toUpperCase());
			if (StringUtil.isNull(cn) || StringUtil.isNull(sn) || authc != 1) {
				logger.error("用户未绑定印章");
				throw new EInvoiceException("用户未绑定印章！");
			}
		} else {
			logger.error("验证签名失败");
			throw new EInvoiceException("验证签名失败！");
		}
		ReturnData authData = checkGenerateAuth(eInvoiceDTO);
		if (0==authData.getCode()) {
			logger.error("开票权限验证失败");
			throw new EInvoiceException(authData.getMsg());
		}
		int result= ieteInvoiceMapper.updateBillActive(eInvoiceDTO.getAdmdivId(), eInvoiceDTO.getAgencyId(), year, eInvoiceDTO.geteInvoiceNumber(), 1, 0, eInvoiceDTO.getChecker(),2,eInvoiceDTO.getBillTypeID());//改票号为已使用状态
		if (0>=result) {
			logger.error("票号核销失败");
			throw new EInvoiceException("票号核销失败！");
		}
		int gflag = ieteInvoiceMapper.insertByEntity(eInvoiceDTO);//保存主表信息
		if (0>=gflag) {
			logger.error("保存主表信息失败");
			throw new EInvoiceException("保存主表信息失败！");
		}
		for (int i = 0; i < eInvoiceDTO.getDetailDTOList().size(); i++) {
			EInvoiceDetailDTO eInvoiceDetailDTO = eInvoiceDTO.getDetailDTOList().get(i);
			gflag = ieGenerateDetailMapper.insertSelective(eInvoiceDetailDTO);
			if (gflag <= 0) {
				logger.error("开票失败：保存开票明细信息失败，明细信息：" + eInvoiceDetailDTO.getItemCode());
				throw new EInvoiceException("保存开票明细信息失败！");
			}
		}
		ReturnData signData = makeEinvoice(eInvoiceDTO, signMap.get("xmlSign"));
		if (0>=signData.getCode()) {
			logger.error("获取签名xml失败:"+signData.getMsg());
			throw new EInvoiceException("获取签名xml失败！");
			//进行回滚
		}
		String eInvoiceXML = (String) signData.getSingleData();
		//20181112 更新表中的XML字段值
		int xmlflag = ieteInvoiceMapper.updateEinvoiceXmlByEinvoiceId(eInvoiceDTO.geteInvoiceId(),eInvoiceXML);
		if (xmlflag<=0)
		{
			logger.error("保存XML失败,开票失败");
			throw new EInvoiceException("保存XML失败！");
		}
		eInvoiceDTO.seteInvoiceXml(eInvoiceXML);
		return 1;
	}

    private String checkParams(OrderDTO orderDTO,ChargeBillDTO chargeBillDTO,OrderDescDTO orderDescDTO,
                               List<OrderDetailDTO> orderDetailDTOList,EInvoiceDTO eInvoiceDTO,
                               List<EInvoiceDetailDTO> detailDTOList,int userID){
        int admDivO = orderDTO.getAdmDiv();
        int agencyO = orderDTO.getHall() > 0 ? orderDTO.getHall() : orderDTO.getAgency();
        int billTypeO = chargeBillDTO.getBillType();
        int agencyIDE = eInvoiceDTO.getAgencyId();
        int admdivIdE = eInvoiceDTO.getAdmdivId();
        int billTypeIDE = eInvoiceDTO.getBillTypeID();
        double amtO = orderDTO.getAmt();
        double totalAmountE = eInvoiceDTO.getTotalAmount();
        int detailCountO = orderDetailDTOList == null ? 0 : orderDetailDTOList.size();
        int detailCountE = detailDTOList == null ? 0 : detailDTOList.size();
        if(admDivO != admdivIdE || agencyO != agencyIDE || billTypeO != billTypeIDE
                || amtO != totalAmountE || detailCountO != detailCountE || detailCountE == 0){
            logger.info(LOGGER_HEADER + " 传入的参数格式错误");
            return "参数格式错误";
        }
        double detailAmtO = 0.00;
        double detailAmtE = 0.00;
        boolean flag = false;
        for (OrderDetailDTO orderDetailDTO : orderDetailDTOList) {
            if(StringUtil.isNull(orderDetailDTO.getBusiOrderNO())){
                flag = true;
            }
            double tempAmt = orderDetailDTO.getAmt();
            detailAmtO = DoubleUtil.add(detailAmtO,tempAmt).doubleValue();
        }
        for (EInvoiceDetailDTO eInvoiceDetailDTO : detailDTOList) {
            double tempAmt = eInvoiceDetailDTO.getItemAmount();
            detailAmtE = DoubleUtil.add(detailAmtE,tempAmt).doubleValue();
        }
        if(detailAmtO != detailAmtE || detailAmtE != totalAmountE){
            logger.info(LOGGER_HEADER + " 明细金额汇总值异常");
            return "明细金额汇总值异常";
        }

//        List<UserDTO> users = ieteInvoiceMapper.getUserInfoByUserID(userID);
//        if(users == null || users.size() != 1){
//            logger.info(LOGGER_HEADER + " 用户不存在:" + userID);
//            return "用户不存在";
//        }
//        UserDTO userDTO = users.get(0);
//        String userCN = userDTO.getUser_CN();
//        String userSN = userDTO.getUser_SN();
//        int count = ieteInvoiceMapper.getAuthcByCNSN(userCN,userSN);
//        if(StringUtil.isNull(userCN) || StringUtil.isNull(userSN) || count != 1){
//            logger.info(LOGGER_HEADER + " 用户未绑定印章:" + userID);
//            return "用户未绑定印章";
//        }
        String busiOrderNOO = orderDTO.getBusiOrderNO();
        String busiOrderNOC = chargeBillDTO.getBusiOrderNO();
        String busiOrderNODesc = orderDescDTO.getBusiOrderNO();
        String payCode = eInvoiceDTO.getPayCode();
        if(StringUtil.isNull(busiOrderNOC) || StringUtil.isNull(busiOrderNOO)
                || flag || StringUtil.isNull(payCode)
                || StringUtil.isNull(busiOrderNODesc)){
            logger.info(LOGGER_HEADER + " 电子缴款码为空");
            return "电子缴款码为空";
        }
        String billNum = chargeBillDTO.getBillNum();
        String eInvoiceCode = eInvoiceDTO.geteInvoiceCode();
        String eInvoiceNumber = eInvoiceDTO.geteInvoiceNumber();
        if(StringUtil.isNull(billNum) || StringUtil.isNull(eInvoiceCode) || StringUtil.isNull(eInvoiceNumber)){
            logger.info(LOGGER_HEADER + " 电子票据号码为空");
            return "电子票据号码为空";
        }
        if(!billNum.equals(eInvoiceCode.concat(eInvoiceNumber))){
            logger.info(LOGGER_HEADER + " 电子票据号码不相符");
            return "电子票据号码不相符";
        }
        return "";
    }
    @Override
    public ReturnData checkGenerateAuth(EInvoiceDTO eInvoiceDTO)  {
        ReturnData returnData = new ReturnData();
        //判断eInvoiceDTO中的内容,是否必填项为空值
        logger.info("EIGenerateService.java ==== >>>> 开始执行验证eInvoiceDTO必填项是否空值-----------------");
        if (StringUtil.isNull(eInvoiceDTO.geteInvoiceTag())
                ||StringUtil.isNull(eInvoiceDTO.geteInvoiceId())
                ||StringUtil.isNull(eInvoiceDTO.getVersion())
                ||StringUtil.isNull(eInvoiceDTO.geteInvoiceName())
                ||StringUtil.isNull(eInvoiceDTO.geteInvoiceCode())
                ||StringUtil.isNull(eInvoiceDTO.geteInvoiceNumber())
                ||StringUtil.isNull(eInvoiceDTO.geteInvoiceSpecimenCode())
                ||StringUtil.isNull(eInvoiceDTO.getSuperVisorAreaCode())
                ||StringUtil.isNull(eInvoiceDTO.getIssueDate())
                ||StringUtil.isNull(eInvoiceDTO.getIssueTime())
                ||StringUtil.isNull(eInvoiceDTO.getInvoicingPartyCode())
                ||StringUtil.isNull(eInvoiceDTO.getInvoicingPartyName())
                ||StringUtil.isNull(eInvoiceDTO.getPayerPartyType())
                ||StringUtil.isNull(eInvoiceDTO.getHandlingPerson())
                ||StringUtil.isNull(eInvoiceDTO.getChecker())
                ||StringUtil.isNull(eInvoiceDTO.getAgencySealId())
                ||StringUtil.isNull(eInvoiceDTO.getAgencySealHash())
                ||StringUtil.isNull(eInvoiceDTO.getAgencySealName())
                ||null==eInvoiceDTO.getTotalAmount()) {
            returnData.setCode(0);
            returnData.setMsg("开票参数必填项有空值,开票失败");
            logger.error("开票参数必填项有空值");
            return returnData;
        }
        logger.info("EIGenerateService.java ==== >>>> 开始执行验证eInvoiceDTO是否有明细项目信息-----------------");

        List<EInvoiceDetailDTO> detailDTOList = eInvoiceDTO.getDetailDTOList();
        if (detailDTOList == null) {
            returnData.setCode(0);
            returnData.setMsg("开票失败：请填写明细项目信息！");
            logger.error("开票失败：请填写明细项目信息！EIGenerateService.java 521行");
            return returnData;
        }
        for (EInvoiceDetailDTO eInvoiceDetailDTO : detailDTOList) {
            if (StringUtil.isNull(eInvoiceDetailDTO.getItemCode())||
                    StringUtil.isNull(eInvoiceDetailDTO.getItemName())||
                    null==eInvoiceDetailDTO.getItemAmount()) {
                returnData.setCode(0);
                returnData.setMsg("开票失败：明细项目信息中必填项为空");
                logger.error("明细项目信息中必填项为空");
                return returnData;
            }
        }
        /**
         * 验证票号是否可用
         */
        String year = ieteInvoiceMapper.getYear();
        int count = ieteInvoiceMapper.verifiInvoiceNumberIsEnable(eInvoiceDTO.geteInvoiceNumber(), eInvoiceDTO.getBillTypeID(), eInvoiceDTO.getAgencyId(), year);
        if (count == 0) {
            returnData.setCode(0);
            returnData.setMsg("票号不可用，请重新申请！");
            logger.error("票号不可用");
            return returnData;
        }
        /**
         * 验证开票日期是否同一天
         */
        String date = ieteInvoiceMapper.getDate().replace("-","");
        if (!date.equals(eInvoiceDTO.getIssueDate())) {
            returnData.setCode(0);
            returnData.setMsg("开票失败:开票日期不准确,请校验..");
            logger.error("开票日期不准确");
            return returnData;
        }

        logger.info("EIGenerateService.java ==== >>>> 开始执行获取票据种类（防止票据种类不存在的情况）-----------------");
        /**
         * EInvoiceSpecimenCode方式
         */
        HashMap<String, Object> parameMap = new HashMap<>();
        parameMap.put("einvoicespecimencode", eInvoiceDTO.geteInvoiceSpecimenCode());
        parameMap.put("billtyid", eInvoiceDTO.getBillTypeID());
        List<Map<String, Object>> templateList  = ieteInvoiceMapper.getBillTypeAndTemplate(parameMap);
        if (CollectionUtils.isEmpty(templateList)) {
            returnData.setCode(0);
            returnData.setMsg("开票失败：票据种类未挂接模板或种类与模板不一致");
            logger.error("开票失败：票据种类未挂接模板或种类与模板不一致");
            return returnData;
        }

        Map agencymap = ieteInvoiceMapper.searchAgencyByAgencyId(eInvoiceDTO.getAgencyId());
        if(null==agencymap){
            returnData.setCode(0);
            returnData.setMsg("开票失败：开票单位代码错误或开票单位不存在！");
            logger.error("开票失败：开票单位代码错误或开票单位不存在！EIGenerateService.java 590");
            return returnData;
        }
        //验证是否挂接改票据
        Integer billCount = ieteInvoiceMapper.isHaveEiBill(eInvoiceDTO.getAgencyId(), eInvoiceDTO.getBillTypeID());
        if (null==billCount||1!=billCount) {
            returnData.setCode(0);
            returnData.setMsg("开票失败：开票单位未挂接改票据种类或挂接多个！");
            logger.error("开票失败：开票单位未挂接改票据种类或挂接多个");
            return returnData;
        }
        returnData.setCode(1);
        returnData.setMsg("验证权限成功");
        returnData.setSingleData(eInvoiceDTO);
        return returnData;
    }

    @Override
    public ReturnData makeEinvoice(EInvoiceDTO eInvoiceDTO,String agencySignXml){
        ReturnData returnData = new ReturnData();
        logger.info("EIGenerateService.java ==== >>>> 开始执行财政签名！-----------------21");
        ReturnData returnDataSupervisor = ieInvoiceSignatureService.ExchangeEInvoiceSupervisorSignature(eInvoiceDTO,agencySignXml);
        if (returnDataSupervisor.getCode() == 0) {
            logger.error("开票失败：票据信息财政签名失败！ EIGenerateService.java 731行");
            returnData.setCode(0);
            returnData.setMsg("开票失败：财政签名失败，请联系省级财政管理员！");
            return returnData;
        }
        Map mapSupervisor = (Map) returnDataSupervisor.getSingleData();
        String supervisorXml = (String) mapSupervisor.get("xmlsign");
        logger.info("[开票]--财政验签签名后的xml--\n" + supervisorXml);

        StringBuilder eInvoiceXML = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        eInvoiceXML.append("<EInvoice>" + eInvoiceDTO.getSignOriginData() + "<EInvoiceSignature>");//签名原文
        eInvoiceXML.append(agencySignXml);//单位签名节点
        eInvoiceXML.append(supervisorXml);//财政签名节点
        eInvoiceXML.append("</EInvoiceSignature></EInvoice>");

        logger.info("开票成功");
        returnData.setCode(1);
        returnData.setMsg("开票,加签成功");
        returnData.setSingleData(eInvoiceXML.toString());
        return returnData;
    }

    @Override
    public int checkRemainBill(int admDivID, int agencyID, int billTypeID, int count) {
        int dbCount = ieteInvoiceMapper.getRemainBillCount(admDivID,agencyID,billTypeID);
        logger.info("当前单位：" + agencyID + ",在当前票据种类：" + billTypeID + "，余票：" + dbCount + "，批量开票：" + count);
        if(dbCount > count){
            logger.info("当前单位：" + agencyID + ",在当前票据种类：" + billTypeID + "，余票充足，可以开票");
            return 1;
        }else if(dbCount == count){
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(admDivID).append("-").append(agencyID).append("-").append(billTypeID).append("-").append(1);
            String value = stringBuilder.toString();
            logger.info("当前单位：" + agencyID + ",在当前票据种类：" + billTypeID + "，余票等于开票数，触发发放逻辑:value值：" + value);
            stringRedisTemplate.opsForList().leftPush(BATCH_ISSUE_QUEUE_NAME,value);
            return 1;
        }else{
            int threShold = ieteInvoiceMapper.getThreshold(agencyID,billTypeID);
            if(threShold <= 0){
                logger.info("当前单位：" + agencyID + ",在当前票据种类：" + billTypeID + "未设置阈值");
                throw new EInvoiceException("未设置电子票据的阈值");
            }else{
                if(threShold < count){
                    logger.info("当前单位：" + agencyID + ",在当前票据种类：" + billTypeID + "，阈值小于批量开票数据：" + threShold + "-" + count);
                    //如果阈值小于开票量 那么直接发放 count / threShold + 1 次
                    int times = (count - dbCount) / threShold + 1;
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append(admDivID).append("-").append(agencyID).append("-").append(billTypeID).append("-").append(times);
                    String value = stringBuilder.toString();
                    logger.info("当前单位：" + agencyID + ",在当前票据种类：" + billTypeID + "，余票等于开票数，触发发放逻辑:value值：" + value);
                    stringRedisTemplate.opsForList().leftPush(BATCH_ISSUE_QUEUE_NAME,value);
                    boolean flag = true;
                    int index = 0;
                    while (flag){
                        if(index == 5){
                            flag = false;
                        }
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            logger.error("线程等待发生异常" + e);
                        }
                        dbCount = ieteInvoiceMapper.getRemainBillCount(admDivID,agencyID,billTypeID);
                        index ++;
                        if(dbCount > count){
                            flag = false;
                        }
                    }
                    return 1;
                }else{
                    logger.info("当前单位：" + agencyID + ",在当前票据种类：" + billTypeID + "，阈值大于批量开票数据：" + threShold + "-" + count);
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append(admDivID).append("-").append(agencyID).append("-").append(billTypeID).append("-").append(1);
                    String value = stringBuilder.toString();
                    logger.info("当前单位：" + agencyID + ",在当前票据种类：" + billTypeID + "，余票等于开票数，触发发放逻辑:value值：" + value);
                    stringRedisTemplate.opsForList().leftPush(BATCH_ISSUE_QUEUE_NAME,value);
                    boolean flag = true;
                    int index = 0;
                    while (flag){
                        if(index == 5){
                            flag = false;
                        }
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            logger.error("线程等待发生异常" + e);
                        }
                        dbCount = ieteInvoiceMapper.getRemainBillCount(admDivID,agencyID,billTypeID);
                        index ++;
                        if(dbCount > count){
                            flag = false;
                        }
                    }
                    return 1;
                }
            }
        }
    }

    @Override
    public List<OrderChargeBillViewDTO> getEBankPayOrderList(int admDivID, int agencyID, String payBankDTS, String payBankDTE, String busiOrderNO, String payer, double amt, int incomeWay, int state) {
        String yearS = "";
        String yearE = "";
        String startTime="";
        String EndTime="";
        if (!StringUtil.isNull(payBankDTS)) {
            yearS = payBankDTS.substring(0,4);
            startTime= payBankDTS.substring(0,10);
        }
        if (!StringUtil.isNull(payBankDTE)) {
            yearE = payBankDTE.substring(0,4);
            EndTime = payBankDTE.substring(0,10);
        }
        String year = "";
        if(!yearS.equals("") && !yearE.equals("")){
            if(yearE.equals(yearS)){
                year = yearE;
            }else{
                year = "in( " + yearS + ", " + yearE + " )";
            }
        }else{
            if(!yearS.equals("")) year = yearS;
            if(!yearE.equals("")) year = yearE;
        }
        List<OrderChargeBillViewDTO> eBankPayOrderList = ieteInvoiceMapper.getEBankPayOrderList(year, admDivID, agencyID, startTime, EndTime, busiOrderNO, payer, amt, incomeWay, state);
        return eBankPayOrderList;
    }

    @Override
    @Transactional
    public int updateOrderAndSaveETbill(OrderChargebillDTO orderChargebillDTO, EInvoiceDTO eInvoiceDTO) {
        logger.info("----------> 电子票据划卡：银联 POS 进入更新票据信息" );
        // 1. 获取订单号
        OrderDTO orderDTO = orderChargebillDTO.getOrderDTO();
        ChargeBillDTO chargeBillDTO = orderChargebillDTO.getChargeBillDTO();
        String busiOrderNO = orderDTO.getBusiOrderNO();
        String billSN = orderDTO.getBillSN();
        if(StringUtil.isNull(busiOrderNO) || StringUtil.isNull(billSN)){
            logger.info("----------> 电子票据划卡，更新电子票据：电子缴款码获取失败");
        }
        OrderDescDTO orderDescDTO = orderDTO.getOrderDescDTO();
        String signOriXML = iGenerateOriSignService.generateBusitype17SignXML(orderChargebillDTO);
        String signData = orderChargebillDTO.getSignData();
        logger.info("POS机打印票据，前台传入密文：" + signData);
        logger.info("POS机打印票据，前台传入原文：" + orderChargebillDTO.getSignOriginData());
        logger.info("POS机打印票据，后台组装原文：" + signOriXML);

        Map<String, String> result = ietVoucherService.verifyCert("", signOriXML, signData);
        String code = result.get("code") == null ? "0" : result.get("code");
        logger.info(LOGGER_HEADER + " 订单号：" + busiOrderNO + "的验签结果为：" + code);
        if(!code.equals("1")){
            throw new EInvoiceException("验签失败" + result.get("msg"));
        }
        // 1.1 验证是否存在错误
        String msg = checkParams(busiOrderNO,orderDTO,chargeBillDTO);
        if(!msg.equals("")){
            logger.info("----------> 电子票据划卡，更新电子票据出错：" + msg);
            throw new EInvoiceException(msg);
        }
        // 3. 开始更新订单
        String clientNum = orderDTO.getClientNum();
        String terminalNum = orderDTO.getTerminalNum();
        String sysNum = orderDTO.getSysNum();
        String payerAccount = orderDTO.getPayerAccount();
        // 3.1 更新订单的 POS 信息
        int ret = iOrderInnerService.updatePosInfoByNo(busiOrderNO,clientNum,terminalNum,sysNum,payerAccount);
        if(ret <= 0){
            logger.info("----------> 电子票据划卡，更新电子票据：更新订单信息失败" + busiOrderNO);
            throw new EInvoiceException("保存失败-订单" + busiOrderNO + "更新失败");
        }
        // 3.2 更新票据描述表
        ret = iChargeBillService.updateBillInfo(chargeBillDTO);
        String billNum = chargeBillDTO.getBillNum();
        if(ret <= 0){
            logger.info("电子票据划卡，更新电子票据：更新票据描述表失败");
            throw new EInvoiceException("保存失败-票号" + billNum + "更新信息失败");
        }
        // 3.3 更新 T_FSBILLACTIVE
        int billType = chargeBillDTO.getBillType();
        int keyWord = chargeBillDTO.getKeyWord();
        int admDiv = orderDTO.getAdmDiv();
        ret = iBillAcitveMapper.updateIsChecked(billNum.substring(8), billType, keyWord, orderDTO.getHall(), orderDTO.getAgency(), admDiv);
        if(ret <= 0){
            logger.info("电子票据划卡，更新电子票据：更新票据核销表失败：" + billNum);
            throw new BusiDataBaseException("保存失败-票号" + billNum + "核销失败");
        }
        String serverDateTime = iCommonService.getServerDateTime();
        ETVoucherDTO etVoucherDTO = iGenerateVoucherService.generateETVoucherDTO(orderDTO.getBillSN(),orderChargebillDTO.getSignData(),null,chargeBillDTO.getBillNum(),-1);
        if(etVoucherDTO == null){
            logger.info(LOGGER_HEADER + "保存凭证失败null");
            throw new EInvoiceException("保存失败");
        }
        etVoucherDTO.setIsVerify(1);
        etVoucherDTO.setSignData(signData);
        etVoucherDTO.setSignOriginData(signOriXML);
        etVoucherDTO.setWriteDate(serverDateTime);
        ret = ietVoucherService.saveETVoucherDTO(etVoucherDTO);
        if(ret <= 0){
            logger.info(LOGGER_HEADER + "保存凭证数据失败");
            throw new EInvoiceException("保存凭证数据失败");
        }
        // 5.保存电子票据
        String email = orderDescDTO == null || StringUtil.isNull(orderDescDTO.geteMail()) ? "" : orderDescDTO.geteMail();
        if(!email.equals(""))
            eInvoiceDTO.setEmail(orderDescDTO.geteMail());
        ret = generateEInvoice(eInvoiceDTO);
        if(ret <=0 ){
            logger.info(LOGGER_HEADER + "电子票据保存失败");
            throw new EInvoiceException("电子票据保存失败");
        }
        //生成png文件
        createPNG(eInvoiceDTO);
        //生成pdf文件
        //createPDF(eInvoiceDTO);
        //生成凭证文件
        CreateVoucherFileVSbankSeal(etVoucherDTO);
        return 1;
    }

	@Override
	@Transactional
	public int batchSaveETbill(List<OrderChargebillDTO> orderChargebillDTOList, List<EInvoiceDTO> eInvoiceDTOList) {
		List<EInvoiceDTO> createEInvoiceDTOList = new ArrayList<>();
		List<Map<String,String>> phoneList = new ArrayList<>();
		String serverDate=iCommonService.getServerDate();
		int size = orderChargebillDTOList.size();
		//验证参数长度是否一致，一个orderchargebilldto对应一个einvoicedto
		if(orderChargebillDTOList.size()!=eInvoiceDTOList.size()){
			logger.warn("orderChargebillDTOList和eInvoiceDTOList的长度不一致");
			throw new EInvoiceException("参数格式错误");
		}
		int count = size % 200 == 0 ? size / 200 : (size / 200 + 1);
		for (int i = 0; i < count; i++) {
			List<OrderChargebillDTO> tempOrderChargebillDTOs = new ArrayList<>();
			List<EInvoiceDTO> tempEInvoiceDTOs = new ArrayList<>();
			List<ChargeBillDTO> tempChargeBillDTOs = new ArrayList<>();
			if(count == 1){
				tempOrderChargebillDTOs = orderChargebillDTOList.subList(0,size);
				tempEInvoiceDTOs = eInvoiceDTOList.subList(0,size);
			}else{
				if(i != (count-1) ){
					tempOrderChargebillDTOs = orderChargebillDTOList.subList(i * 200, (i + 1) * 200);
					tempEInvoiceDTOs = eInvoiceDTOList.subList(i * 200, (i + 1) * 200);
				}else{
					tempOrderChargebillDTOs = orderChargebillDTOList.subList(i*200,size);
					tempEInvoiceDTOs = eInvoiceDTOList.subList(i*200,size);
				}
			}
			//保存票据描述
			if(tempOrderChargebillDTOs.size()!=tempEInvoiceDTOs.size()){
				throw new EInvoiceException("批量开具电子票据出错");
			}
			for (int k=0;k<tempOrderChargebillDTOs.size();k++){
				String issueDate = tempEInvoiceDTOs.get(k).getIssueDate();
				StringBuilder stringBuilder = new StringBuilder();
				stringBuilder.append(issueDate.substring(0,4)).append("-").append(issueDate.substring(4,6)).append("-").append(issueDate.substring(6));
				tempOrderChargebillDTOs.get(k).getChargeBillDTO().setFillDate(stringBuilder.toString());
				//tempOrderChargebillDTOs.get(k).getChargeBillDTO().setWriteDate(serverDateTime);
				tempChargeBillDTOs.add(tempOrderChargebillDTOs.get(k).getChargeBillDTO());
			}
			for (int j=0;j<tempOrderChargebillDTOs.size();j++){
				// 1. 获取订单号
				//OrderDTO orderDTO = tempOrderChargebillDTOs.get(j).getOrderDTO();
				String busiOrderNO=tempOrderChargebillDTOs.get(j).getChargeBillDTO().getBusiOrderNO();
				OrderDTO orderDTO = ietOrderService.getOrderByOrderNO(busiOrderNO);
				if(orderDTO==null){
					logger.info("----------> 批量开具电子票据出错：未找到匹配的订单信息" );
					throw new EInvoiceException("批量开具电子票据出错：未找到匹配的订单信息");
				}
				ChargeBillDTO chargeBillDTO = tempOrderChargebillDTOs.get(j).getChargeBillDTO();
				//String busiOrderNO = orderDTO.getBusiOrderNO();
				String billSN = orderDTO.getBillSN();
				String billNum=chargeBillDTO.getBillNum();
				EInvoiceDTO eInvoiceDTO = tempEInvoiceDTOs.get(j);
				if(StringUtil.isNull(busiOrderNO) || StringUtil.isNull(billSN)){
					logger.info("----------> 批量开具电子票据出错：电子缴款码获取失败");
				}
				List<OrderDescDTO> orderDescDTOS = iEticketNumMapper.getOrderDesc(busiOrderNO);
				if(orderDescDTOS!=null&&orderDescDTOS.size()==1){
					OrderDescDTO orderDescDTO = orderDescDTOS.get(0);
					Map<String,String> msgMap = new HashMap<>();
					String einvoiceCode = billNum.substring(0, 8);
					String einvoiceNumber = billNum.substring(8, 18);
					String verifyCode = chargeBillDTO.getVertifyCode();
					DecimalFormat fnum = new DecimalFormat("0.00");
					StringBuilder msg = new StringBuilder();
					List<BillTypeDTO> billTypeDTOS = iEticketNumMapper.getBillTypeByID(chargeBillDTO.getBillType());
					String billName = "";
					String agencyName="";
					if(billTypeDTOS != null && billTypeDTOS.size() > 0){
						billName = billTypeDTOS.get(0).getItemName();
					}
					List<AgencyDTO> agencyInfoByID = ieteInvoiceMapper.getAgencyInfoByID(orderDTO.getAgency());
					if(agencyInfoByID != null && agencyInfoByID.size() > 0){
						agencyName = agencyInfoByID.get(0).getItemName();
					}
					msg.append("【河北财政非税】").append(serverDate)
							.append(agencyName)
							.append("向交款人").append(orderDTO.getPayer()).append("开具").append(billName).append("，")
							.append("票据号码").append(einvoiceCode)
							.append("票据号").append(einvoiceNumber)
							.append("校验码").append(verifyCode)
							.append("金额").append(fnum.format(orderDTO.getAmt()))
							.append("查验网站：http://pjcy.hebcz.cn");
					msgMap.put("phone",orderDescDTO.getLinkTel());
					msgMap.put("userID",String.valueOf(orderDTO.getWriteUser()));
					msgMap.put("content",msg.toString());
					phoneList.add(msgMap);
					String email = orderDescDTO == null || StringUtil.isNull(orderDescDTO.geteMail()) ? "" : orderDescDTO.geteMail();
					if(!email.equals("")) {
						eInvoiceDTO.setEmail(orderDescDTO.geteMail());
					}
				}
				//String signOriXML = iGenerateOriSignService.generateBusitype17SignXML(tempOrderChargebillDTOs.get(j));
				//String signData = tempOrderChargebillDTOs.get(j).getSignData();
				//logger.info("批量开具电子票据，前台传入密文：" + signData);
				//logger.info("批量开具电子票据，前台传入原文：" + tempOrderChargebillDTOs.get(j).getSignOriginData());
				//logger.info("批量开具电子票据，后台组装原文：" + signOriXML);
				//Map<String, String> result = ietVoucherService.verifyCert("", signOriXML, signData);
				//String code = result.get("code") == null ? "0" : result.get("code");
				//logger.info(LOGGER_HEADER + " 订单号：" + busiOrderNO + "的验签结果为：" + code);
				//if(!code.equals("1")){
				//	throw new EInvoiceException("验签失败" + result.get("msg"));
				//}
				// 2 验证是否存在错误
				String msg = checkParams(busiOrderNO,orderDTO,chargeBillDTO);
				if(!msg.equals("")){
					logger.info("----------> 批量开具电子票据出错：" + msg);
					throw new EInvoiceException(msg);
				}
				// 3更新 T_FSBILLACTIVE
				//String billNum = chargeBillDTO.getBillNum();
				int billType = chargeBillDTO.getBillType();
				int keyWord = chargeBillDTO.getKeyWord();
				int admDiv = orderDTO.getAdmDiv();

				// 4 保存凭证
				//String serverDateTime = iCommonService.getServerDateTime();
				//ETVoucherDTO etVoucherDTO = iGenerateVoucherService.generateETVoucherDTO(orderDTO.getBillSN(),orderChargebillDTOList.get(j).getSignData(),null,chargeBillDTO.getBillNum(),-1);
				//if(etVoucherDTO == null){
				//	logger.info(LOGGER_HEADER + "保存凭证失败null");
				//	throw new EInvoiceException("保存失败");
				//}
				//etVoucherDTO.setIsVerify(1);
				//etVoucherDTO.setSignData(signData);
				//etVoucherDTO.setSignOriginData(signOriXML);
				//etVoucherDTO.setWriteDate(serverDateTime);
				//ret = ietVoucherService.saveETVoucherDTO(etVoucherDTO);
				//if(ret <= 0){
				//	logger.info(LOGGER_HEADER + "保存凭证数据失败");
				//	throw new EInvoiceException("保存凭证数据失败");
				//}
				//createETVoucherDTOList.add(etVoucherDTO);

				//保存einvoice
				eInvoiceDTO.setIsEnable(1);
				int ret = generateEInvoice(eInvoiceDTO);
				if(ret <=0 ){
					logger.info(LOGGER_HEADER + "电子票据保存失败");
					throw new EInvoiceException("电子票据保存失败");
				}
				ret = iBillAcitveMapper.updateIsChecked(billNum.substring(8), billType, keyWord, orderDTO.getHall(), orderDTO.getAgency(), admDiv);
				if(ret <= 0){
					logger.info("批量开具电子票据出错：更新票据核销表失败：" + billNum);
					throw new BusiDataBaseException("保存失败-票号" + billNum + "核销失败");
				}
				createEInvoiceDTOList.add(eInvoiceDTO);

			}
			//保存票据
			int num = ietChargebillMapper.batchSaveChargebill(tempChargeBillDTOs);
		}
		//生成png文件
		for (EInvoiceDTO eInvoiceDTO : createEInvoiceDTOList) {
			createPNG(eInvoiceDTO);
		}
		//生成pdf文件
		/*for (EInvoiceDTO eInvoiceDTO : createEInvoiceDTOList) {
			createPDF(eInvoiceDTO);
		}*/
		//生成凭证文件
		//for (ETVoucherDTO etVoucherDTO : createETVoucherDTOList) {
		//	CreateVoucherFileVSbankSeal(etVoucherDTO);
		//}
		//批量发送短信
		for (Map<String, String> stringStringMap : phoneList) {
			String recID = stringStringMap.get("userID");
			String content = stringStringMap.get("content");
			String linkTel = stringStringMap.get("phone");
			sendPhoneMsg(recID,content,linkTel,serverDate);
		}
		return 1;
	}

	private void sendPhoneMsg(String recID,String content,String linkTel,String today){
        Runnable task = new Runnable() {
            @Override
            public void run() {
                iSendMessageService.sendMessageByPhoneNew(recID,recID,content,linkTel,today);
            }
        };
        taskExecutor.execute(task);
    }

    private String checkParams(String busiOrderNO,OrderDTO orderDTO,ChargeBillDTO chargeBillDTO){
        // 1.1 验证订单号是否已经收款
        int count = iOrderInnerService.isBankAffirmByNO(busiOrderNO);
        String msg = "";
        if(count <= 0){
            logger.info("----------> 更新银联 POS 票据时，订单" + busiOrderNO + "未收款");
            msg = "保存失败-订单" + busiOrderNO + "未收款";
        }
        // 1.2 验证订单是否已经开票
        count = iChargeBillService.isBillByNo(busiOrderNO);
        if(count > 0){
            logger.info("----------> 更新银联 POS 票据时，订单" + busiOrderNO + "已经开票");
            msg = "保存失败-订单" + busiOrderNO + "已经开票";
        }
        // 2. 验证传入的票号是否可用
        String billNum = chargeBillDTO.getBillNum();
        int keyWord = chargeBillDTO.getKeyWord();
        int billType = chargeBillDTO.getBillType();
        int agency = orderDTO.getHall();
        if(agency <= 0){
            agency = orderDTO.getAgency();
        }
        int admDiv = orderDTO.getAdmDiv();
        String vertifyCode = chargeBillDTO.getVertifyCode();
        String fillDate = chargeBillDTO.getFillDate();
        if(StringUtil.isNull(billNum) || StringUtil.isNull(vertifyCode) || StringUtil.isNull(fillDate)){
            logger.info("----------> 更新银联 POS 票据时，票据信息不完整");
            msg = "保存失败-票据信息不完整";
        }
        // 2.1 验证 T_FSBILLACTIVE 表是否存在有效的票号
        count = iBillAcitveMapper.searchETBillActive(admDiv,agency,billNum,billType);
        if(count < 0){
            logger.info("----------> 更新银联 POS 票据时，传入的票号" + billNum + "不可用");
            msg = "保存失败-票号" + billNum + "不可用";
        }
        // 2.2 验证票号是否在 T_FSCHARGEBILL 已经存在
        count = iChargeBillService.isUsedByBillNum(billNum,billType,keyWord);
        if(count > 0){
            logger.info("----------> 更新银联 POS 票据时，传入的票号" + billNum + "已经使用");
            msg = "保存失败-票号" + billNum + "已经使用";
        }
        return msg;
    }

    private void createPNG(EInvoiceDTO eInvoiceDTO){
        Runnable taskPng = () -> {
            String name = Thread.currentThread().getName() + "-png加入队列";
            Thread.currentThread().setName(name);
            ietQueueService.setPngQueue(eInvoiceDTO);
        };
        taskExecutor.execute(taskPng);
    }
    private void createPDF(EInvoiceDTO eInvoiceDTO){
        Runnable taskPdf = new Runnable() {
            @Override
            public void run() {
                String name = Thread.currentThread().getName() + "-制作pdf";
                Thread.currentThread().setName(name);
                BigDecimal numberOfMoney = new BigDecimal(eInvoiceDTO.getTotalAmount());
                eInvoiceDTO.setTotalAmountUp(NumberToCN.number2CNMontrayUnit(numberOfMoney));
                Map<String, String> cert = ieteInvoiceMapper.selectAgencyCert(eInvoiceDTO.getAgencyId());
                Map sealInfo = ieteInvoiceMapper.getSealInfoByAgencyId(eInvoiceDTO.getAgencySealId(),eInvoiceDTO.getAgencyId());
                ReturnData returnData = createFile.createPdfFile(eInvoiceDTO, sealInfo, cert);
                if (returnData.getCode()>=1) {
                    ieteInvoiceMapper.updatePdfStatusByEIvoiceID(eInvoiceDTO.geteInvoiceId());
                }else{
                    ieteInvoiceMapper.updateErrorPdfStatusByEIvoiceID(eInvoiceDTO.geteInvoiceId(), returnData.getMsg());
                }
            }
        };
        taskExecutor.execute(taskPdf);
    }
    private void createVoucher(ETVoucherDTO etVoucherDTO){
        Runnable taskVoucher = () -> {
            String name = Thread.currentThread().getName() + "-开票凭证";
            Thread.currentThread().setName(name);
            ietQueueService.setVouncherQueue(etVoucherDTO);
        };
        taskExecutor.execute(taskVoucher);
    }
    private void CreateVoucherFileVSbankSeal(ETVoucherDTO etVoucherDTO){
        Runnable taskVoucher = () -> {
            String name = Thread.currentThread().getName() + "-开票凭证";
            Thread.currentThread().setName(name);
            ietQueueService.setVouncherVsBankQueue(etVoucherDTO);
        };
        taskExecutor.execute(taskVoucher);
    }

	public static void main(String[] args) {

	}
}
