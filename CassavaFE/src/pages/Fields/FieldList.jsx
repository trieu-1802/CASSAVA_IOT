// src/pages/Fields/FieldList.jsx
import React, { useState } from 'react';
import { Table, Button, Space, Typography, Popconfirm, message, Card } from 'antd';
import { 
  PlusOutlined, 
  DownloadOutlined, 
  EditOutlined, 
  DeleteOutlined, 
  EyeOutlined,
  CopyOutlined 
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';

// 1. Import Component Popup Thêm/Sửa tham số cánh đồng
import FieldModal from './components/FieldModal';

const { Title } = Typography;
// 1. Lấy thông tin user từ localStorage để kiểm tra quyền
  const userData = JSON.parse(localStorage.getItem('user'));
 // const isAdmin = userData?.admin === true; // Kiểm tra trường admin trong Token/User
const isAdmin = false;
const FieldList = () => {
  const navigate = useNavigate();

  // --- CÁC STATE QUẢN LÝ DỮ LIỆU VÀ GIAO DIỆN ---
  
  // State quản lý việc ẩn/hiện popup Modal
  const [isModalOpen, setIsModalOpen] = useState(false);
  
  // State lưu trữ dữ liệu của cánh đồng đang được chọn để sửa (nếu có)
  const [editingField, setEditingField] = useState(null);

  // Dữ liệu danh sách cánh đồng (Sau này sẽ gọi API từ BE trả về)
  // Mình thêm sẵn các tham số tưới tiêu để khi bấm "Sửa", nó hiện sẵn lên form
  const [fields, setFields] = useState([
    {
      id: '1',
      name: 'Cánh đồng sắn khu A',
      area: 1.5,
      plantingDate: '2025-12-01',
      //status: 'Phát triển tốt',
      model: 'tưới tay',
      totalHoles: 1000,
      dripRate: 2.5,
      holeSpacing: 30,
      targetHumidity: 65,
    },
    {
      id: '2',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
     // status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '3',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
     // status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '4',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
      //status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '5',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
      //status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '6',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
      //status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '7',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
      //status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '8',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
     // status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '9',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
      //status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '10',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
      //status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
        {
      id: '11',
      name: 'Cánh đồng sắn khu B',
      area: 2.0,
      plantingDate: '2026-01-15',
     // status: 'Cần tưới nước',
      model: 'tưới tự động',
      totalHoles: 1500,
      dripRate: 3.0,
      holeSpacing: 40,
      targetHumidity: 70,
    },
  ]);

  // --- CÁC HÀM XỬ LÝ SỰ KIỆN NÚT BẤM ---

  // Chuyển hướng sang trang chi tiết (3 Tab)
  const handleViewDetail = (id) => {
    navigate(`/fields/${id}`);
  };

  // Mở popup để THÊM MỚI
  const handleAddNew = () => {
    setEditingField(null); // Đảm bảo form trống rỗng
    setIsModalOpen(true);  // Bật popup
  };

  // Mở popup để SỬA THAM SỐ
  const handleEditParams = (record) => {
    setEditingField(record); // Nạp dữ liệu của dòng hiện tại vào form
    setIsModalOpen(true);    // Bật popup
  };

  // Xử lý khi người dùng bấm "Lưu lại" ở trong Popup
  const handleModalSubmit = (values) => {
    if (editingField) {
      // Logic cập nhật nếu đang ở chế độ Sửa
      const updatedFields = fields.map(field => 
        field.id === editingField.id ? { ...field, ...values } : field
      );
      setFields(updatedFields);
      message.success(`Đã cập nhật thông số cho: ${values.name}`);
    } else {
      // Logic thêm mới vào danh sách
      const newField = {
        ...values,
        id: Date.now().toString(), // Tạo một ID tạm thời
        plantingDate: new Date().toISOString().split('T')[0], // Gán ngày hôm nay
        status: 'Mới tạo',
      };
      setFields([...fields, newField]);
      message.success(`Đã thêm mới cánh đồng: ${values.name}`);
    }
    setIsModalOpen(false); // Lưu xong thì đóng popup
  };

  // Xóa cánh đồng
  //const handleDelete = (id) => {
   // const newData = fields.filter(item => item.id !== id);
  //  setFields(newData);
//    message.success('Đã xóa cánh đồng thành công!');
 // };
const handleDelete = (id) => {
  

  if (isAdmin) {
    // KỊCH BẢN CHO ADMIN: Xóa "mềm" trên giao diện
    const newData = fields.filter(item => (item._id !== id && item.id !== id));
    setFields(newData);
    message.success('Đã xóa cánh đồng thành công! (Chế độ Admin)');
  } else {
    // KỊCH BẢN CHO USER THƯỜNG: Báo lỗi
    message.error('Lỗi: Bạn không có quyền thực hiện hành động này!');
  }
};
// Hàm sao chép cánh đồng
const handleClone = (record) => {
  // Tạo một bản sao mới từ record hiện tại
  const clonedField = {
    ...record,
    id: Date.now().toString(), // Tạo ID mới duy nhất bằng timestamp
    name: `${record.name} (Bản sao)`, // Thêm chữ (Bản sao) để dễ phân biệt
    status: 'Mới tạo', // Reset trạng thái nếu cần
    plantingDate: new Date().toISOString().split('T')[0], // Gán ngày hiện tại
  };

  // Cập nhật vào danh sách state
  setFields([...fields, clonedField]);
  message.success(`Đã sao chép thành công cánh đồng: ${record.name}`);
};

  // Tải file thời tiết
  const handleDownloadWeather = () => {
    // TODO: Tích hợp thư viện xlsx để xuất file thật
    message.success('Đang tải xuống file dataWeather.xlsx...');
  };

  // --- CẤU HÌNH CÁC CỘT CHO BẢNG ---
  const columns = [
    {
      title: 'Tên cánh đồng',
      dataIndex: 'name',
      key: 'name',
      render: (text) => <strong>{text}</strong>,
    },
    {
      title: 'Diện tích (ha)',
      dataIndex: 'area',
      key: 'area',
    },
    {
      title: 'Ngày bắt đầu trồng',
      dataIndex: 'plantingDate',
      key: 'plantingDate',
    },
    //{
     // title: 'Trạng thái',
     // dataIndex: 'status',
     // key: 'status',
    //},
    {
      title: 'Chế độ tưới',
      dataIndex: 'model',
      key: 'model',
    },
    {
      title: 'Hành động',
      key: 'action',
      align: 'center',
      render: (_, record) => (
        <Space size="middle">
          <Button 
            type="primary" 
            icon={<EyeOutlined />} 
            onClick={() => handleViewDetail(record.id)}
          >
            Chi tiết
          </Button>
          <Button 
            icon={<EditOutlined />} 
            onClick={() => handleEditParams(record)}
          >
            Sửa tham số
          </Button>
          {/* Nút Clone mới thêm vào */}
        <Button 
          icon={<CopyOutlined />} 
          onClick={() => handleClone(record)}
          title="Sao chép cánh đồng"
        >
          Clone
        </Button>
          {isAdmin?( <Popconfirm
            title="Xóa cánh đồng"
            description={`Bạn có chắc chắn muốn xóa "${record.name}" không?`}
            onConfirm={() => handleDelete(record.id)}
            okText="Có, Xóa"
            cancelText="Hủy"
          >
            <Button danger icon={<DeleteOutlined />}>Xóa</Button>
          </Popconfirm>):
          (
            <span style={{ color: '#999' }}></span>)
          }
        </Space>
      ),
    },
  ];

  // --- RENDER GIAO DIỆN ---
  return (
    <div style={{ padding: '24px' }}>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <Title level={3} style={{ margin: 0 }}>Danh sách cánh đồng</Title>
          <Space>
          {/*}  <Button 
              type="dashed" 
              icon={<DownloadOutlined />} 
              onClick={handleDownloadWeather}
            >
              Tải dữ liệu thời tiết
            </Button> */}
            
            {/* Gọi hàm handleAddNew khi bấm nút Thêm */}
            { isAdmin ? (<Button 
              type="primary" 
              icon={<PlusOutlined />} 
              onClick={handleAddNew}
            >
              Thêm cánh đồng
            </Button>): null
            }
          </Space>
        </div>

        {/* Bảng hiển thị danh sách */}
        <Table 
          columns={columns} 
          dataSource={fields} 
          rowKey="id" 
          pagination={{ 
            pageSize: 8,
            position: ['bottomCenter'] // Căn giữa bộ chuyển trang cho đẹp 

          }} 
        />
      </Card>

      {/* 2. Nhúng Component Popup vào đây */}
      {/* Nó sẽ tàng hình cho đến khi isModalOpen được set thành true */}
      <FieldModal 
        open={isModalOpen} 
        onCancel={() => setIsModalOpen(false)} 
        onSubmit={handleModalSubmit}
        initialData={editingField}
      />
    </div>
  );
};

export default FieldList;